-- 作者元数据独立成表：一张 book_id → 作者 / 性别 / 评分 的长期缓存。
--
-- 为什么从 novel_rank 拆出来：novel_rank 的一行等于「某本书在某分类某榜单的一次快照」，
-- 掉榜清理（覆盖整榜时删行）会把这一行连作者性别一起删掉，书重新上榜还得再请求一次
-- 第三方 /info；而 /info 是共享机器、约 19KB/本，容量极小，重复取数是纯浪费。
-- 拆表后元数据跟书走、不跟榜走：掉榜只删榜单行，作者信息留着，再上榜直接命中。
--
-- novel_rank 上的 author_gender / rating 继续保留，作为页面查询的展示冗余：
-- 筛选、排序、分页都在 novel_rank 上做，不能为了取一个性别再 join 一次。
-- 两份数据的同步点是「拉榜写库时回填」与「补齐取到新值时双写」，见 NovelServiceImpl.applyMeta
-- 与 BookMetaEnricher.updateRows。
CREATE TABLE IF NOT EXISTS author_meta (
    book_id VARCHAR(50) PRIMARY KEY,
    author VARCHAR(100),
    -- 0=女 / 1=男 / NULL=取过但第三方没有可信值（profile_gender 缺失或为 2）
    gender TINYINT,
    -- 性别只取一次：有值即不再请求；书已下架也会盖章，避免反复试注定失败的请求
    gender_checked_time TIMESTAMP,
    -- 评分 0–10，NULL=取过但该平台无评分
    rating DECIMAL(3,1),
    -- 评分按 rating-refresh-days（默认 5 天）刷新
    rating_checked_time TIMESTAMP,
    create_time TIMESTAMP,
    update_time_db TIMESTAMP
);
-- 待补统计按「性别没取过」筛，单列索引足够（取过之后基本不再按它查）
CREATE INDEX IF NOT EXISTS idx_author_meta_gender_checked ON author_meta (gender_checked_time);

-- 存量搬迁：novel_rank 里已经取到过的作者信息先收进来，上线即可命中，不必重跑一轮补齐。
-- 只搬「取过数」的书：没取过的行搬进来也只是空壳，反而让待补统计多一层判断。
INSERT INTO author_meta (book_id, author, gender, gender_checked_time, rating, rating_checked_time,
                         create_time, update_time_db)
SELECT book_id, MAX(author), MAX(author_gender), MAX(author_gender_checked_time),
       MAX(rating), MAX(rating_checked_time), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM novel_rank
WHERE book_id IS NOT NULL
GROUP BY book_id
HAVING MAX(author_gender) IS NOT NULL OR MAX(author_gender_checked_time) IS NOT NULL
    OR MAX(rating) IS NOT NULL OR MAX(rating_checked_time) IS NOT NULL;
