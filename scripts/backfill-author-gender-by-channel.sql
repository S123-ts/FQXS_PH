-- 作者性别按频道兜底：存量回填（2026-10-25）
--
-- 背景：第三方 /info 对一部分书只给 profile_gender=2（未知），且作者主页资料全空
-- （无百科、0 粉丝、0 作品），短期内拿不到更可信的值。用户明确要求页面上不再出现
-- 「未知」这一档，因此：第三方判未知的书按书籍频道记性别（男频=男 / 女频=女）。
--
-- ⚠️ 这是产品取舍，不是数据结论：作者性别与频道并不强相关（女写男频、男写女频
-- 都常见）。回填只动「第三方本来就没给出性别」的行，第三方给过可信值（0/1）的
-- 一行都不碰。
--
-- ⚠️ H2 库被应用独占，必须在 fqxs 停服期间执行；执行前先跑前两条 SELECT 看规模。
--
-- 用法（服务停稳后）：
--   java -cp h2-2.3.232.jar org.h2.tools.Shell \
--     -url "jdbc:h2:file:/opt/fqxs/data/fqxs;AUTO_SERVER=TRUE" \
--     -user sa -password "" < backfill-author-gender-by-channel.sql
-- 版本号按 jar 里实际的 h2-*.jar 改。

-- ① 看规模：权威表里第三方判未知、且已盖章的行
SELECT COUNT(*) AS author_meta_unknown
  FROM AUTHOR_META
 WHERE GENDER IS NULL
   AND GENDER_CHECKED_TIME IS NOT NULL;

-- ② 看规模：榜单行上作者性别未知、但本行频道已知的行
SELECT COUNT(*) AS novel_rank_unknown
  FROM NOVEL_RANK
 WHERE AUTHOR_GENDER IS NULL
   AND GENDER IN (0, 1);

-- ③ 回填权威表 author_meta：未知 → 该书在榜单行上的频道（1=男 / 0=女）。
--    同一 bookId 在多个分类各有一行、频道理论上一致；取 MAX 只为确定一个值，
--    不一致时记男频（保守：男频书的总量占比更高）。没有榜单行的书不回填。
UPDATE AUTHOR_META m
   SET GENDER = (SELECT MAX(r.GENDER)
                   FROM NOVEL_RANK r
                  WHERE r.BOOK_ID = m.BOOK_ID
                    AND r.GENDER IN (0, 1))
 WHERE m.GENDER IS NULL
   AND m.GENDER_CHECKED_TIME IS NOT NULL
   AND EXISTS (SELECT 1
                 FROM NOVEL_RANK r
                WHERE r.BOOK_ID = m.BOOK_ID
                  AND r.GENDER IN (0, 1));

-- ④ 回填展示冗余 novel_rank：作者性别未知 → 本行自己的频道。
--    author_gender_checked_time 不动：时间戳管的是「还要不要再问第三方」，
--    这里只是把已知答案补上，问过这件事依然算数，评分到期时也不会被覆盖。
UPDATE NOVEL_RANK r
   SET AUTHOR_GENDER = r.GENDER
 WHERE r.AUTHOR_GENDER IS NULL
   AND r.GENDER IN (0, 1);

-- ⑤ 复核：两条都应该是 0
SELECT COUNT(*) AS author_meta_still_unknown
  FROM AUTHOR_META
 WHERE GENDER IS NULL
   AND GENDER_CHECKED_TIME IS NOT NULL;
SELECT COUNT(*) AS novel_rank_still_unknown
  FROM NOVEL_RANK
 WHERE AUTHOR_GENDER IS NULL
   AND GENDER IN (0, 1);
