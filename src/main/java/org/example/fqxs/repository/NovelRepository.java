package org.example.fqxs.repository;

import org.example.fqxs.entity.Novel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface NovelRepository extends JpaRepository<Novel, Long> {

    /**
     * rank 以字符串落库，直接 ORDER BY 会得到字典序（第 10 名排在第 2 名前），
     * 因此显式按数值排序。
     */
    @Query("SELECT n FROM Novel n WHERE n.categoryCode = :categoryCode ORDER BY CAST(n.rank AS integer) ASC")
    List<Novel> findByCategoryCodeOrderByRankNumeric(@Param("categoryCode") String categoryCode);

    /** 分类视图按榜单类型取各自分区：阅读榜/新书榜互不混淆。 */
    @Query("SELECT n FROM Novel n WHERE n.categoryCode = :categoryCode AND n.rankType = :rankType "
            + "ORDER BY CAST(n.rank AS integer) ASC")
    List<Novel> findByCategoryCodeAndRankTypeOrderByRankNumeric(@Param("categoryCode") String categoryCode,
                                                                @Param("rankType") String rankType);

    /** “全部”视图：只取当前榜单类型的分区，两榜数据并存时不互相串。 */
    List<Novel> findByRankType(String rankType);

    List<Novel> findByCategoryCodeInAndRankType(Collection<String> categoryCodes, String rankType);

    /**
     * “全部”视图服务端分页（限定分类范围）：筛选条件全部下推，排序由 Pageable 传入。
     *
     * <p>{@code status} 传空串表示不筛；{@code minWan} 为开区间下限（>），
     * {@code maxWan} 为闭区间上限（<=），null 表示该侧不限。</p>
     */
    @Query("SELECT n FROM Novel n WHERE n.rankType = :rankType AND n.categoryCode IN :codes "
            + "AND n.isBlocked = :blockedOnly "
            + "AND (:watchedOnly = false OR n.isWatched = true) "
            + "AND (:status = '' OR n.status = :status) "
            + "AND (:minWan IS NULL OR n.wordCountWan > :minWan) "
            + "AND (:maxWan IS NULL OR n.wordCountWan <= :maxWan) "
            + "AND (:authorGender = '' "
            + "     OR (:authorGender = 'male' AND n.authorGender = 1) "
            + "     OR (:authorGender = 'female' AND n.authorGender = 0) "
            + "     OR (:authorGender = 'unknown' AND n.authorGender IS NULL)) "
            + "AND (:minRating IS NULL OR (n.rating IS NOT NULL AND n.rating >= :minRating))")
    Page<Novel> pageAllBooksByCodes(@Param("codes") Collection<String> codes,
                                    @Param("rankType") String rankType,
                                    @Param("blockedOnly") boolean blockedOnly,
                                    @Param("watchedOnly") boolean watchedOnly,
                                    @Param("status") String status,
                                    @Param("minWan") Double minWan,
                                    @Param("maxWan") Double maxWan,
                                    @Param("authorGender") String authorGender,
                                    @Param("minRating") Double minRating,
                                    Pageable pageable);

    /** 与 {@link #pageAllBooksByCodes} 同条件，但不限分类（排查接口的“当前榜单全部分区”）。 */
    @Query("SELECT n FROM Novel n WHERE n.rankType = :rankType "
            + "AND n.isBlocked = :blockedOnly "
            + "AND (:watchedOnly = false OR n.isWatched = true) "
            + "AND (:status = '' OR n.status = :status) "
            + "AND (:minWan IS NULL OR n.wordCountWan > :minWan) "
            + "AND (:maxWan IS NULL OR n.wordCountWan <= :maxWan) "
            + "AND (:authorGender = '' "
            + "     OR (:authorGender = 'male' AND n.authorGender = 1) "
            + "     OR (:authorGender = 'female' AND n.authorGender = 0) "
            + "     OR (:authorGender = 'unknown' AND n.authorGender IS NULL)) "
            + "AND (:minRating IS NULL OR (n.rating IS NOT NULL AND n.rating >= :minRating))")
    Page<Novel> pageAllBooks(@Param("rankType") String rankType,
                             @Param("blockedOnly") boolean blockedOnly,
                             @Param("watchedOnly") boolean watchedOnly,
                             @Param("status") String status,
                             @Param("minWan") Double minWan,
                             @Param("maxWan") Double maxWan,
                             @Param("authorGender") String authorGender,
                             @Param("minRating") Double minRating,
                             Pageable pageable);

    /**
     * 作者信息补全的候选范围：当前榜单按热度去重后的前 N 本（代表行 = MIN(id)）。
     * 只在用户点“补作者信息”时调用，绝不一次拉全库。
     */
    @Query("SELECT n FROM Novel n WHERE n.rankType = :rankType AND n.isBlocked = false "
            + "AND n.bookId IS NOT NULL "
            + "AND n.id IN (SELECT MIN(n2.id) FROM Novel n2 WHERE n2.rankType = :rankType "
            + "           AND n2.isBlocked = false AND n2.bookId IS NOT NULL GROUP BY n2.bookId) "
            + "ORDER BY n.readCountRaw DESC")
    List<Novel> findMetaTopBooks(@Param("rankType") String rankType, Pageable pageable);

    /**
     * 启动后台补齐的全库待补清单（按书去重、按热度降序）：性别没取过，或评分没取过/已过刷新周期。
     * 与 {@link #findMetaTopBooks} 的区别是不限前 N——一次只返回 bookId 列表，
     * 逐本请求的节拍由调用方控制，绝不一次拉全库实体。
     *
     * <p>判据从 novel_rank 换到 author_meta（V20 起作者元数据独立成表）：只看榜单行的话，
     * 书掉榜被清理、重新上榜时会退回「待补」，已经取到过的数据又要花一次额度重取。
     * 连表之后「这本书在榜上」和「这本书还没取过数」两个条件才各自成立。</p>
     *
     * <p>原生 SQL：JPQL 不能在没有实体关联的情况下 LEFT JOIN 另一张表。
     * 排序沿用页面「全部」总榜的口径（热度降序），补齐顺序与用户看到的顺序一致。</p>
     *
     * @param genderFilter 频道过滤：-1 不过滤 / 1 只男频 / 0 只女频（自用只拉男频时额度全给男频）
     */
    @Query(value = "SELECT n.book_id FROM novel_rank n "
            + "LEFT JOIN author_meta a ON a.book_id = n.book_id "
            + "WHERE n.is_blocked = FALSE AND n.book_id IS NOT NULL "
            + "AND (:genderFilter < 0 OR n.gender = :genderFilter) "
            + "AND (a.book_id IS NULL OR a.gender_checked_time IS NULL "
            + "     OR a.rating_checked_time IS NULL OR a.rating_checked_time < :staleBefore) "
            + "GROUP BY n.book_id ORDER BY MAX(n.read_count_raw) DESC", nativeQuery = true)
    List<String> findBookIdsNeedingMeta(@Param("staleBefore") LocalDateTime staleBefore,
                                        @Param("genderFilter") int genderFilter);

    /**
     * 字数数值列的回填：没有数值的行，以及解析结果为 0 的行（解析规则修正后要重算）。
     * 原始字数为空的行不参与——它们本来就不该命中任何字数区间。
     */
    @Query("SELECT n FROM Novel n WHERE n.wordCount IS NOT NULL AND n.wordCount <> '' "
            + "AND (n.wordCountWan IS NULL OR n.wordCountWan = 0)")
    List<Novel> findWordCountRowsToBackfill();

    /** upsert 的批量预读：同一本书可挂在多个分类下，必须按 (categoryCode, rankType, bookId) 定位。 */
    List<Novel> findByCategoryCodeAndRankTypeAndBookIdIn(String categoryCode, String rankType,
                                                         Collection<String> bookIds);

    /** 拉黑按书操作，同一本书在多个分类/榜单各有一行，返回全部行一起开关。 */
    List<Novel> findAllByBookId(String bookId);

    /**
     * 历史数据回填：category_code 仍为 NULL 的行按旧分类名补上新 code。
     * 幂等——回填过的行不再命中 IS NULL 谓词；映射不到的乱数据保持 NULL。
     */
    @Modifying
    @Query("UPDATE Novel n SET n.categoryCode = :code WHERE n.categoryName = :name AND n.categoryCode IS NULL")
    int backfillCategoryCode(@Param("name") String name, @Param("code") String code);

    /**
     * 清掉该分类当前榜单分区中本次榜单已不再包含的掉榜行；调用方需保证传入的是完整榜单。
     * 只清自己分区：阅读榜的书不因新书榜抓取被误删。已拉黑的行必须留下：
     * 拉黑是长期屏蔽名单，书掉榜不等于屏蔽失效。
     */
    @Modifying
    @Query("DELETE FROM Novel n WHERE n.categoryCode = ?1 AND n.rankType = ?2 "
            + "AND n.bookId NOT IN ?3 AND n.isBlocked = false")
    int deleteStaleByCategoryCodeAndRankType(String categoryCode, String rankType,
                                             Collection<String> keptBookIds);

    @Modifying
    @Query("DELETE FROM Novel")
    int deleteAllRecords();

    @Modifying
    @Query("DELETE FROM Novel n WHERE n.categoryCode = ?1")
    int deleteByCategoryCode(String categoryCode);

    /**
     * 免费初筛候选：指定引擎的<b>任一勾选型号</b>都未判过的书，按热度从高到低取。
     * 同一本书跨多个分类各有一行，必须 GROUP BY bookId 去重，否则一次批量会把
     * 同一本书判 N 遍；排序用聚合后的热度，避免 ORDER BY 非分组列报错。
     * “缺结论”用 NOT IN 子查询对 verdict 表反查；型号池参与比对——勾选多个型号时，
     * 只要任一型号判过即视为判过，避免同一本书被多个型号重复消耗额度。
     * 调用方应随机挑页（见 AiDetectServiceImpl.sweepFree）：固定取第 0 页会让
     * 热度低的书永远轮不到，且某本书持续失败时会一直占据队首。
     */
    @Query("SELECT n.bookId FROM Novel n WHERE n.isBlocked = false "
            + "AND n.bookId NOT IN (SELECT v.bookId FROM NovelAiVerdict v "
            + "WHERE v.engine = :engine AND v.model IN :models) "
            + "GROUP BY n.bookId ORDER BY MAX(n.readCountRaw) DESC")
    List<String> findBookIdsMissingEngineVerdict(@Param("engine") String engine,
                                                 @Param("models") Collection<String> models,
                                                 Pageable pageable);

    /**
     * 后台逐章校验候选：指定引擎还没「查满 N 章」的书，按热度从高到低取。
     *
     * <p>判据用明细行的 {@code chapters_checked}——逐章校验的游标就存在那里，没有明细行的书
     * 自然算 0 章、排在最前。同一本书跨多个分类各有一行，必须 GROUP BY 去重，否则一轮里
     * 同一本会被推进多次。</p>
     *
     * <p>「查完」是<b>按型号</b>算的：逐章校验一次只推进一个型号的一章，本次范围内的每个型号都要
     * 各自查满 {@code chapters} 章。所以排除条件是「该引擎下这本书在 {@code rowModels} 里的行数已经
     * 凑满 {@code modelCount} 且每行都查满」——只看某一行达标就把书踢出队列，其余型号永远轮不到。</p>
     *
     * <p>只数 {@code rowModels}（带 {@code #verify} 后缀的行标识）是有意的：后台按<b>家族代表</b>推进，
     * 池里被去重掉的型号（如 {@code ernie-4.5-turbo-128k}）可能留有手动校验的旧行，
     * 把它们算进来会让这本书被误判成「已查完」。</p>
     */
    @Query("SELECT n.bookId FROM Novel n WHERE n.isBlocked = false AND n.bookId IS NOT NULL "
            + "AND n.bookId NOT IN (SELECT v.bookId FROM NovelAiVerdict v "
            + "WHERE v.engine = :engine AND v.model IN :rowModels "
            + "GROUP BY v.bookId HAVING COUNT(v) >= :modelCount AND MIN(v.chaptersChecked) >= :chapters) "
            + "GROUP BY n.bookId ORDER BY MAX(n.readCountRaw) DESC")
    List<String> findBookIdsNeedingVerify(@Param("engine") String engine,
                                         @Param("rowModels") Collection<String> rowModels,
                                         @Param("modelCount") int modelCount,
                                         @Param("chapters") int chapters,
                                         Pageable pageable);

    /** 指定引擎与型号池尚未判定的书籍数（按书去重），批量初筛据此计算随机页。 */
    @Query("SELECT COUNT(DISTINCT n.bookId) FROM Novel n WHERE n.isBlocked = false "
            + "AND n.bookId NOT IN (SELECT v.bookId FROM NovelAiVerdict v "
            + "WHERE v.engine = :engine AND v.model IN :models)")
    long countBookIdsMissingEngineVerdict(@Param("engine") String engine,
                                          @Param("models") Collection<String> models);

    /** 某个判定标记下的书籍数（按书去重），用于页面展示判定分布。 */
    @Query("SELECT COUNT(DISTINCT n.bookId) FROM Novel n WHERE n.aiFlag = :aiFlag")
    long countDistinctBooksByAiFlag(@Param("aiFlag") int aiFlag);

    /**
     * 书源搜索：书名或作者模糊匹配，按 bookId 去重后分页。
     *
     * <p>去重原因：同一本书挂在多个分类榜各一行，不去重搜索页会出现多个同名条目。
     * 取 {@code MIN(id)} 的代表行；{@code bookId IS NULL} 的历史脏行无法定位详情，不参与。
     * 排序「精确书名 → 书名前缀 → 书名包含 → 作者包含」，同档按热度降序——找某本具体的书时，
     * 精确命中必须排第一。通配符由服务层转义并用 {@code ESCAPE '!'} 声明，调用方传不进 {@code %}。</p>
     */
    @Query("SELECT n FROM Novel n WHERE n.isBlocked = false AND n.bookId IS NOT NULL "
            + "AND (LOWER(n.title) LIKE :like ESCAPE '!' OR LOWER(n.author) LIKE :like ESCAPE '!') "
            + "AND n.id IN (SELECT MIN(n2.id) FROM Novel n2 WHERE n2.isBlocked = false "
            + "AND n2.bookId IS NOT NULL "
            + "AND (LOWER(n2.title) LIKE :like ESCAPE '!' OR LOWER(n2.author) LIKE :like ESCAPE '!') "
            + "GROUP BY n2.bookId) "
            + "ORDER BY CASE WHEN LOWER(n.title) = :keyword THEN 0 "
            + "WHEN LOWER(n.title) LIKE :prefix ESCAPE '!' THEN 1 "
            + "WHEN LOWER(n.title) LIKE :like ESCAPE '!' THEN 2 ELSE 3 END ASC, "
            + "n.readCountRaw DESC")
    List<Novel> searchByKeyword(@Param("keyword") String keyword, @Param("like") String like,
                                @Param("prefix") String prefix, Pageable pageable);

    /** 与 {@link #searchByKeyword} 同条件的总数（按书去重），书源分页靠它判断下一页。 */
    @Query("SELECT COUNT(DISTINCT n.bookId) FROM Novel n WHERE n.isBlocked = false "
            + "AND n.bookId IS NOT NULL "
            + "AND (LOWER(n.title) LIKE :like ESCAPE '!' OR LOWER(n.author) LIKE :like ESCAPE '!')")
    long countSearchKeyword(@Param("like") String like);
}
