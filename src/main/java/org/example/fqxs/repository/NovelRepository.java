package org.example.fqxs.repository;

import org.example.fqxs.entity.Novel;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * 免费初筛候选：指定引擎的**任一勾选型号**都还没判过的书，按热度从高到低取。
     * 同一本书跨多个分类各有一行，必须 GROUP BY bookId 去重，否则一次批量会把
     * 同一本书判N遍；排序用聚合后的热度，避免 ORDER BY 非分组列报错。
     * “缺结论”用 NOT IN 子查询对 verdict 表反查；型号池参与比对——勾选多个型号时，
     * 只要任一型号判过就算判过，避免同一本书被几个型号各烧一次额度。
     * 调用方应随机挑页（见 AiDetectServiceImpl.sweepFree）：固定取第 0 页会让
     * 热度低的书永远轮不到，并且某本持续失败时会次次卡在队首。
     */
    @Query("SELECT n.bookId FROM Novel n WHERE n.isBlocked = false "
            + "AND n.bookId NOT IN (SELECT v.bookId FROM NovelAiVerdict v "
            + "WHERE v.engine = :engine AND v.model IN :models) "
            + "GROUP BY n.bookId ORDER BY MAX(n.readCountRaw) DESC")
    List<String> findBookIdsMissingEngineVerdict(@Param("engine") String engine,
                                                 @Param("models") Collection<String> models,
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
}
