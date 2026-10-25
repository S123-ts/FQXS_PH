package org.example.fqxs.repository;

import org.example.fqxs.entity.NovelAiVerdict;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface NovelAiVerdictRepository extends JpaRepository<NovelAiVerdict, Long> {

    /** 同一引擎的同一型号对同一本书只保留一条：判定前先按它定位，存在即覆盖。 */
    Optional<NovelAiVerdict> findByBookIdAndEngineAndModel(String bookId, String engine, String model);

    /** 该引擎在型号池里的任一型号是否已判过这本书（多模型分摊时用于幂等复用）。 */
    List<NovelAiVerdict> findByBookIdAndEngineAndModelIn(String bookId, String engine,
                                                         Collection<String> models);

    /** 页面渲染用：按当前视图的书批量取全部引擎结论，一次查询不逐行打。 */
    /**
     * 批量取多本书的全部结论行。
     *
     * <p>必须带排序：前端按「逐章命中优先、否则最新」选行，时间相同的行不能靠数据库返回顺序决定。
     * 新的在前，同刻按 id 倒序（后写入的优先）。</p>
     */
    List<NovelAiVerdict> findByBookIdInOrderByCheckedTimeDescIdDesc(Collection<String> bookIds);

    /** 启动回填用：所有出现过明细结论的 bookId（主表口径变更后按它逐本重算）。 */
    @Query("SELECT DISTINCT v.bookId FROM NovelAiVerdict v")
    List<String> findDistinctBookIds();

    /** 引擎维度的已判书数（按 bookId 去重），给状态面板展示每个 API 覆盖了多少本。 */
    long countByEngineAndAiFlag(String engine, int aiFlag);

    /**
     * 把历史行（model=legacy）归属到引擎当前型号：升级后不会凭空触发全量重判。
     *
     * @param engine 引擎标识
     * @param model  当前型号
     * @return 更新行数
     */
    @Modifying
    @Transactional
    @Query("UPDATE NovelAiVerdict v SET v.model = :model WHERE v.engine = :engine AND v.model = 'legacy'")
    int backfillModel(@Param("engine") String engine, @Param("model") String model);
}
