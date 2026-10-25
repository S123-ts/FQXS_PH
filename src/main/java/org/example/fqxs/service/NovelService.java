package org.example.fqxs.service;

import org.example.fqxs.constant.CategoryCatalog;
import org.example.fqxs.entity.Novel;
import org.example.fqxs.model.NovelInfo;
import org.springframework.data.domain.Page;

import java.util.List;

public interface NovelService {

    /**
     * 增量更新：按 (categoryCode, rankType, bookId) 定位，存在则更新、不存在则插入，
     * 保留拉黑状态；返回本次落库统计。rankType 是本次抓取的榜单类型（read/new），
     * 决定写入哪个分区。pruneStale 为 true 时同时清掉该分类该分区中本次榜单
     * 已不包含的掉榜行，只有抓到完整榜单才允许传 true，否则一页数据会把其余名次误删。
     */
    UpsertSummary saveOrUpdateNovelList(List<NovelInfo> novelList, CategoryCatalog category,
                                        String rankType, boolean pruneStale);

    /** 不分榜单类型的全量行，供 /api/db 排查；页面读路径走 findAllBooksFiltered。 */
    List<Novel> findNovelsByCategoryCode(String categoryCode);

    List<Novel> findAllNovels();

    /**
     * “全部”视图读库（服务端分页）：只取 rankType 指定榜单的分区；blockedOnly 为 true 时
     * 反过来只返回已拉黑的书，给“恢复”留出口。watchedOnly 与它是交集：
     * 只返回已关注的书，不改变“未勾选时正常列表仍包含已关注行”的语义。
     *
     * @param page         页码，从 1 开始
     * @param size         每页条数（服务层会钳到上限）
     * @param authorGender 作者性别筛选：male / female / unknown / 空=全部
     * @param minRating    第三方评分下限（0–10），null=不筛；无评分的书不满足下限
     * @return 当前页数据与命中总数
     */
    Page<Novel> findAllBooksFiltered(List<String> categoryCodes, String wordRange, String status,
                                     boolean blockedOnly, boolean watchedOnly, String rankType,
                                     int page, int size, String authorGender, Double minRating);

    /** 库里是否一行都没有（跨全部榜单分区），给前端区分“空库”与“空分区”。 */
    long countAllNovels();

    /** 同一本书可能挂多个分类榜，拉黑/恢复作用于它的全部行（跨榜单类型）。 */
    void setBlocked(String bookId, boolean blocked);

    /**
     * 关注/取消关注，按 bookId 作用于全部行。关注不影响列表可见性，
     * 只是“只看关注”筛选与行内按钮状态的依据。
     */
    void setWatched(String bookId, boolean watched);

    /**
     * 写入免费引擎的判定结论，按 bookId 作用于全部行。
     *
     * <p>主表是**聚合口径**：方法内部按明细重算（任一「引擎 × 型号」的有效结论为 AI 即 2），
     * 参数里的单次结论只在明细缺失时兜底。</p>
     *
     * @param bookId  书籍 ID
     * @param aiFlag  本次判定标记：1=非 AI 2=AI 3=疑似 AI
     * @param aiScore 本次 AI 概率（0–100）
     * @param engine  产出结论的引擎标识
     */
    void applyFreeVerdict(String bookId, int aiFlag, double aiScore, String engine);

    /**
     * 写入手动逐章校验的结论，按 bookId 作用于全部行。各引擎共用此入口；
     * 主表仍按明细聚合——命中自然变 AI 且不被后续非 AI 覆盖，未命中只推进 zq_* 进度。
     *
     * @param bookId          书籍 ID
     * @param engine          产出本次结论的引擎标识（glm-4-flash / qianfan-ernie-speed / qwen2.5-7b / 自定义 id）
     * @param manualFlag      手动校验标记：0=进行中 1=已查章节均非 AI 2=命中 AI
     * @param checkedChapters 已校验章节数
     * @param manualScore     本章 AI 风险占比（0–100）
     * @param aiHit           是否命中 AI；命中经明细聚合成为主表 2
     */
    void applyManualVerifyVerdict(String bookId, String engine, int manualFlag, int checkedChapters,
                                  double manualScore, boolean aiHit);

    /**
     * 启动回填：按明细重算所有已判书籍的主表综合结论。
     *
     * <p>主表口径在 2026-10-25 从「最后写入」改成「按型号聚合」，历史行的值不会自动变；
     * 启动时跑一次幂等重算，把旧口径写的 40 本 AI / 80 本非 AI 纠正到聚合口径。</p>
     *
     * @return 重算成功的书数（明细在、但已不在榜的书会被跳过）
     */
    int recomputeAiAggregates();

    void deleteNovelsByCategoryCode(String categoryCode);

    void deleteAllNovels();

    /** 写库结果统计，skipped 用于暴露被丢弃的脏数据（如缺失 bookId）。 */
    record UpsertSummary(int inserted, int updated, int skipped) {
        public static final UpsertSummary EMPTY = new UpsertSummary(0, 0, 0);
    }
}
