package org.example.fqxs.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.constant.CategoryCatalog;
import org.example.fqxs.entity.AuthorMeta;
import org.example.fqxs.entity.Novel;
import org.example.fqxs.entity.NovelAiVerdict;
import org.example.fqxs.model.NovelInfo;
import org.example.fqxs.repository.AuthorMetaRepository;
import org.example.fqxs.repository.NovelAiVerdictRepository;
import org.example.fqxs.repository.NovelRepository;
import org.example.fqxs.service.NovelService;
import org.example.fqxs.util.DateUtil;
import org.example.fqxs.util.NovelTextParser;
import org.example.fqxs.util.PuaDecoder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NovelServiceImpl implements NovelService {

    /** 单页上限：前端最大只给 100，留一倍余量防止手改参数把整库拉回来。 */
    private static final int MAX_PAGE_SIZE = 200;

    private final NovelRepository novelRepository;
    private final NovelAiVerdictRepository verdictRepository;
    private final AuthorMetaRepository authorMetaRepository;
    private final PuaDecoder puaDecoder;

    /** 逐章校验行的型号后缀：聚合时按去掉后缀的型号与初筛行合并。 */
    private static final String VERIFY_SUFFIX = "#verify";

    @Override
    @Transactional
    public UpsertSummary saveOrUpdateNovelList(List<NovelInfo> novelList, CategoryCatalog category,
                                               String rankType, boolean pruneStale) {
        if (novelList == null || novelList.isEmpty()) {
            return UpsertSummary.EMPTY;
        }

        List<String> bookIds = novelList.stream()
                .map(NovelInfo::getBookId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        // 按 (categoryCode, rankType, bookId) 组合预读：一本书同上男女频多个分类、
        // 同分类又同挂阅读榜/新书榜，各是一行，互不覆盖
        Map<String, Novel> existingByBookId = bookIds.isEmpty()
                ? Collections.emptyMap()
                : novelRepository.findByCategoryCodeAndRankTypeAndBookIdIn(
                        category.getCode(), rankType, bookIds).stream()
                        .collect(Collectors.toMap(Novel::getBookId, novel -> novel, (a, b) -> a));

        int inserted = 0;
        int updated = 0;
        int skipped = 0;
        List<Novel> toSave = new ArrayList<>();

        // 作者元数据（V20 起独立成表）：拉榜时把已知的性别/评分回填到新行，
        // 掉榜重上的书不必再排队重取，页面也不会先显示成「未知」
        Map<String, AuthorMeta> knownMeta = bookIds.isEmpty()
                ? Collections.emptyMap()
                : authorMetaRepository.findAllByBookIdIn(bookIds).stream()
                        .collect(Collectors.toMap(AuthorMeta::getBookId, meta -> meta, (a, b) -> a));

        for (NovelInfo info : novelList) {
            // bookId 缺失不能入库：空串会让所有脏数据被并到同一行上互相覆盖
            if (info.getBookId() == null || info.getBookId().isBlank()) {
                skipped++;
                log.warn("跳过缺失 bookId 的记录: title={}", info.getTitle());
                continue;
            }

            Novel novel = existingByBookId.get(info.getBookId());
            if (novel == null) {
                novel = new Novel();
                novel.setBookId(info.getBookId());
                novel.setIsBlocked(false);
                inserted++;
            } else {
                updated++;
            }
            apply(novel, info, category, rankType);
            applyMeta(novel, knownMeta.get(info.getBookId()));
            toSave.add(novel);
        }

        if (skipped > 0) {
            log.warn("分类[{}]有 {} 条记录因 bookId 为空被跳过，请核对上游字段名", category.getName(), skipped);
        }
        if (!toSave.isEmpty()) {
            novelRepository.saveAll(toSave);
        }
        // 只有抓到完整榜单时才清理掉榜行，否则一页数据会把榜单其余名次误删。
        // bookIds 的判空是承重的：H2 里 x NOT IN (空集) 恒真，去掉会把整分类未拉黑行清空。
        // 只清本次抓取的榜单分区：抓新书榜不能动阅读榜的数据。
        int removed = pruneStale && !bookIds.isEmpty()
                ? novelRepository.deleteStaleByCategoryCodeAndRankType(category.getCode(), rankType, bookIds)
                : 0;
        log.info("分类[{}]({}/{})增量更新: 新增 {} 本, 更新 {} 本, 清理掉榜 {} 本",
                category.getName(), category.getCode(), rankType, inserted, updated, removed);
        return new UpsertSummary(inserted, updated, skipped);
    }

    @Override
    public List<Novel> findNovelsByCategoryCode(String categoryCode) {
        return novelRepository.findByCategoryCodeOrderByRankNumeric(categoryCode);
    }

    @Override
    public List<Novel> findAllNovels() {
        return novelRepository.findAll();
    }

    @Override
    public long countAllNovels() {
        return novelRepository.count();
    }

    /**
     * “全部”视图服务端分页：筛选与排序全部下推，Java 侧只做参数归一。
     * 查询失败一律外抛给 GlobalExceptionHandler：吞成空页会让页面把“库挂了”读成“这里没书”。
     */
    @Override
    public Page<Novel> findAllBooksFiltered(List<String> categoryCodes, String wordRange, String status,
                                            boolean blockedOnly, boolean watchedOnly, String rankType,
                                            int page, int size, String authorGender, Double minRating) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        Pageable pageable = PageRequest.of(safePage - 1, safeSize, sortFor(blockedOnly, watchedOnly));
        // status 空串 = 不筛；字数档位转成开/闭区间，null 表示该侧不限
        String statusFilter = status == null ? "" : status;
        String authorFilter = authorGender == null ? "" : authorGender;
        Double minWan = null;
        Double maxWan = null;
        if ("100w以上".equals(wordRange)) {
            minWan = 100.0;
        } else if ("200w以上".equals(wordRange)) {
            minWan = 200.0;
        } else if ("100w以内".equals(wordRange)) {
            maxWan = 100.0;
        }
        Double ratingFloor = minRating == null || !Double.isFinite(minRating)
                ? null : Math.max(0, Math.min(10, minRating));
        if (categoryCodes == null || categoryCodes.isEmpty()) {
            return novelRepository.pageAllBooks(rankType, blockedOnly, watchedOnly,
                    statusFilter, minWan, maxWan, authorFilter, ratingFloor, pageable);
        }
        return novelRepository.pageAllBooksByCodes(categoryCodes, rankType, blockedOnly, watchedOnly,
                statusFilter, minWan, maxWan, authorFilter, ratingFloor, pageable);
    }

    /** 默认按热度；“已拉黑 / 只看关注”按对应时刻倒序，没有时刻的历史行排最后。 */
    private static Sort sortFor(boolean blockedOnly, boolean watchedOnly) {
        if (watchedOnly) {
            return Sort.by(Order.desc("watchedTime").nullsLast(), Order.desc("readCountRaw"));
        }
        if (blockedOnly) {
            return Sort.by(Order.desc("blockedTime").nullsLast(), Order.desc("readCountRaw"));
        }
        return Sort.by(Order.desc("readCountRaw"));
    }

    @Override
    @Transactional
    public void setBlocked(String bookId, boolean blocked) {
        // 唯一键是 (categoryCode, bookId)，同一本书在每个分类榜各有一行，必须一起开关
        List<Novel> rows = requireRows(bookId);
        for (Novel novel : rows) {
            novel.setIsBlocked(blocked);
            novel.setBlockedTime(blocked ? DateUtil.now() : null);
        }
        novelRepository.saveAll(rows);
        log.info("已{}书籍: {} (bookId={}, 涉及 {} 个分类行)",
                blocked ? "拉黑" : "解除拉黑", rows.get(0).getTitle(), bookId, rows.size());
    }

    /**
     * 关注按 bookId 全行联动：同一本书在多个分类榜各有一行，
     * 只在当前分类行上打标记会让切分类后的状态自相矛盾。
     */
    @Override
    @Transactional
    public void setWatched(String bookId, boolean watched) {
        List<Novel> rows = requireRows(bookId);
        LocalDateTime watchedTime = watched ? DateUtil.now() : null;
        for (Novel novel : rows) {
            novel.setIsWatched(watched);
            novel.setWatchedTime(watchedTime);
        }
        novelRepository.saveAll(rows);
        log.info("已{}关注: {} (bookId={}, 涉及 {} 个分类行)",
                watched ? "添加" : "取消", rows.get(0).getTitle(), bookId, rows.size());
    }

    /**
     * 免费引擎结论按 bookId 全行联动。**主表是聚合口径**（2026-10-25 用户拍板）：
     * 每次写入后按明细重算——每个「引擎 × 型号」取一条有效结论，任一型号为 AI 即主表 2。
     * 这样 GLM 判 AI、ERNIE 判非 AI 时，主表不会被后写入的 ERNIE 覆盖成非 AI。
     */
    @Override
    @Transactional
    public void applyFreeVerdict(String bookId, int aiFlag, double aiScore, String engine) {
        List<Novel> rows = requireRows(bookId);
        if (!recomputeAiAggregate(bookId, rows)) {
            // 没有任何明细可聚合（历史数据/并发清理）：退回调用方给的单次结论
            LocalDateTime checkedTime = DateUtil.now();
            for (Novel novel : rows) {
                novel.setAiFlag(aiFlag);
                novel.setAiScore(aiScore);
                novel.setAiEngine(engine);
                novel.setAiCheckedTime(checkedTime);
            }
            novelRepository.saveAll(rows);
        }
        log.info("免费判定已落库（主表按型号聚合）: bookId={}, 标记={}, 概率={}, 涉及 {} 行",
                bookId, rows.get(0).getAiFlag(), rows.get(0).getAiScore(), rows.size());
    }

    /**
     * 手动逐章校验结论按 bookId 全行联动。
     *
     * <p>命中 AI 的优先级由明细聚合自然保证：`model#verify` 行一旦命中就一直是 2，
     * 后续整本初筛判非 AI 也不会把它覆盖掉（用户要求「手动命中持久」）。未命中只动 zq_*。</p>
     */
    @Override
    @Transactional
    public void applyManualVerifyVerdict(String bookId, String engine, int manualFlag, int checkedChapters,
                                         double manualScore, boolean aiHit) {
        List<Novel> rows = requireRows(bookId);
        // 1. 手动校验进度与结论逐行写入（进度用于前端显示 n/总数）。
        //    这些列原本叫 zq_*，现在承载"最近一次手动校验"的进度，各引擎共用。
        LocalDateTime checkedTime = DateUtil.now();
        for (Novel novel : rows) {
            novel.setZqFlag(manualFlag);
            novel.setZqChaptersChecked(checkedChapters);
            novel.setZqScore(manualScore);
            novel.setZqCheckedTime(checkedTime);
        }
        // 2. 主表综合结论按明细重算：命中自然变 2；未命中不会覆盖别的型号/引擎的 AI 结论
        if (!recomputeAiAggregate(bookId, rows)) {
            novelRepository.saveAll(rows);
        }
        log.info("手动校验已落库: bookId={}, engine={}, 标记={}, 已查 {} 章, 风险占比={}",
                bookId, engine, manualFlag, checkedChapters, manualScore);
    }

    @Override
    public int recomputeAiAggregates() {
        int updated = 0;
        for (String bookId : verdictRepository.findDistinctBookIds()) {
            List<Novel> rows = novelRepository.findAllByBookId(bookId);
            if (!rows.isEmpty() && recomputeAiAggregate(bookId, rows)) {
                updated++;
            }
        }
        return updated;
    }

    /**
     * 按明细重算主表综合结论。
     *
     * <p>每个「引擎 × 型号」的有效结论按优先级取：逐章命中（{@code #verify} 且 2）→
     * 整本初筛 → 逐章未命中的完成结论；随后任一为 AI 就主表 2，其次任一疑似 3，
     * 再次任一非 AI 1。只有进度行（aiFlag=0）时不动主表。</p>
     *
     * @return true 表示已按明细写过主表；false 表示没有明细可依据
     */
    private boolean recomputeAiAggregate(String bookId, List<Novel> rows) {
        List<NovelAiVerdict> verdicts = verdictRepository
                .findByBookIdInOrderByCheckedTimeDescIdDesc(List.of(bookId));
        if (verdicts.isEmpty()) {
            return false;
        }
        Map<String, NovelAiVerdict> verifyHits = new LinkedHashMap<>();
        Map<String, NovelAiVerdict> sweeps = new LinkedHashMap<>();
        Map<String, NovelAiVerdict> verifyDone = new LinkedHashMap<>();
        Map<String, NovelAiVerdict> latestAny = new LinkedHashMap<>();
        for (NovelAiVerdict verdict : verdicts) { // 已按 checked_time desc, id desc
            String key = verdict.getEngine() + "|" + baseModel(verdict.getModel());
            latestAny.putIfAbsent(key, verdict);
            int checked = verdict.getChaptersChecked() == null ? 0 : verdict.getChaptersChecked();
            if (isVerifyRow(verdict)) {
                if (verdict.getAiFlag() == 2) {
                    verifyHits.putIfAbsent(key, verdict);
                } else if (verdict.getAiFlag() > 0 && checked > 0) {
                    verifyDone.putIfAbsent(key, verdict);
                }
            } else if (verdict.getAiFlag() > 0 && checked == 0) {
                sweeps.putIfAbsent(key, verdict);
            }
        }
        NovelAiVerdict chosen = null;
        for (String key : latestAny.keySet()) {
            NovelAiVerdict effective = verifyHits.get(key);
            if (effective == null) {
                effective = sweeps.get(key);
            }
            if (effective == null) {
                effective = verifyDone.get(key);
            }
            if (effective != null && isStronger(effective, chosen)) {
                chosen = effective;
            }
        }
        if (chosen == null) {
            return false;
        }
        double score = chosen.getAiScore() == null ? 0.0 : chosen.getAiScore();
        LocalDateTime checkedTime = chosen.getCheckedTime() == null ? DateUtil.now() : chosen.getCheckedTime();
        for (Novel novel : rows) {
            novel.setAiFlag(chosen.getAiFlag());
            novel.setAiScore(score);
            novel.setAiEngine(chosen.getEngine());
            novel.setAiCheckedTime(checkedTime);
        }
        novelRepository.saveAll(rows);
        return true;
    }

    private static boolean isVerifyRow(NovelAiVerdict verdict) {
        return verdict.getModel() != null && verdict.getModel().endsWith(VERIFY_SUFFIX);
    }

    private static String baseModel(String model) {
        if (model == null) {
            return "";
        }
        return model.endsWith(VERIFY_SUFFIX)
                ? model.substring(0, model.length() - VERIFY_SUFFIX.length())
                : model;
    }

    /** AI > 疑似 > 非 AI；同权重取更新的那条。 */
    private static boolean isStronger(NovelAiVerdict candidate, NovelAiVerdict current) {
        if (current == null) {
            return true;
        }
        int weight = weight(candidate.getAiFlag());
        int currentWeight = weight(current.getAiFlag());
        if (weight != currentWeight) {
            return weight > currentWeight;
        }
        return isNewer(candidate, current);
    }

    private static int weight(int aiFlag) {
        return aiFlag == 2 ? 3 : aiFlag == 3 ? 2 : aiFlag == 1 ? 1 : 0;
    }

    private static boolean isNewer(NovelAiVerdict candidate, NovelAiVerdict current) {
        LocalDateTime candidateTime = candidate.getCheckedTime();
        LocalDateTime currentTime = current.getCheckedTime();
        if (candidateTime == null || currentTime == null) {
            return candidateTime != null;
        }
        int compared = candidateTime.compareTo(currentTime);
        if (compared != 0) {
            return compared > 0;
        }
        Long candidateId = candidate.getId();
        Long currentId = current.getId();
        return candidateId != null && (currentId == null || candidateId > currentId);
    }

    @Override
    @Transactional
    public void deleteNovelsByCategoryCode(String categoryCode) {
        int deleted = novelRepository.deleteByCategoryCode(categoryCode);
        log.warn("已清空分类[{}]的 {} 条记录", categoryCode, deleted);
    }

    @Override
    @Transactional
    public void deleteAllNovels() {
        int deleted = novelRepository.deleteAllRecords();
        log.warn("已清空 novel_rank 全部 {} 条记录", deleted);
    }

    /**
     * 分类身份取自入参目录而不是 NovelInfo：抓取端不设置时也不会写入 null 分类。
     *
     * <p>书名、作者、简介在上游同样被替换成私用区（PUA）字符——前端靠番茄的 woff2 字体
     * 把 U+E3E8–U+E555 画成汉字，因此页面显示正常，而库里的字节与接口返回都是残缺的。
     * 入库是唯一的文本写入口，在此还原一次，之后搜索、导出、AI 提示词才能拿到汉字。
     * 未混淆文本原样通过，重复调用结果一致。</p>
     */
    private void apply(Novel novel, NovelInfo info, CategoryCatalog category, String rankType) {
        novel.setRank(info.getRank());
        novel.setTitle(puaDecoder.decodeIfNeeded(info.getTitle()));
        novel.setAuthor(puaDecoder.decodeIfNeeded(info.getAuthor()));
        novel.setDescription(puaDecoder.decodeIfNeeded(info.getDescription()));
        novel.setStatus(info.getStatus());
        novel.setReadCount(info.getReadCount());
        novel.setReadCountRaw(info.getReadCountRaw());
        novel.setLastChapter(puaDecoder.decodeIfNeeded(info.getLastChapter()));
        novel.setUpdateTime(info.getUpdateTime());
        novel.setThumbUri(info.getThumbUri());
        novel.setCategoryName(category.getName());
        novel.setCategoryCode(category.getCode());
        novel.setGender(category.getGender());
        novel.setWordCount(info.getWordCount());
        novel.setWordCountWan(wordCountWanOf(info.getWordCount()));
        novel.setRankType(rankType);
    }

    /**
     * 拉榜时把 author_meta 里已有的作者信息回填到榜单行。
     *
     * <p>为什么必须在拉榜时回填：一本书可能掉榜被清理删行、过几天又上榜，新插的行
     * author_gender 是空的。不回填的话页面先显示「未知」，补齐线程还要为它再排一次队、
     * 再花一次第三方额度——而元数据表里早就存着。回填之后「取过一次就永久命中」才真正成立。</p>
     *
     * <p>只填空缺：行上已有性别时保留行上的值（存量数据优先）；评分以元数据为准，它会定期刷新。</p>
     */
    private static void applyMeta(Novel novel, AuthorMeta meta) {
        if (meta == null) {
            return;
        }
        if (novel.getAuthorGenderCheckedTime() == null && meta.getGenderCheckedTime() != null) {
            novel.setAuthorGender(meta.getGender());
            novel.setAuthorGenderCheckedTime(meta.getGenderCheckedTime());
        }
        if (meta.getRating() != null) {
            novel.setRating(meta.getRating());
        }
        if (meta.getRatingCheckedTime() != null) {
            novel.setRatingCheckedTime(meta.getRatingCheckedTime());
        }
    }

    /** 原始字数 → “万”数值；没有字数时留 null（不参与任何字数区间）。 */
    private static Double wordCountWanOf(String wordCount) {
        if (wordCount == null || wordCount.isBlank()) {
            return null;
        }
        return NovelTextParser.parseWordCountToWan(wordCount);
    }

    /** 取一本书的全部分类行：拉黑/关注/结论写回都以整本书为单位。 */
    private List<Novel> requireRows(String bookId) {
        if (bookId == null || bookId.isBlank()) {
            throw new IllegalArgumentException("bookId 不能为空");
        }
        List<Novel> rows = novelRepository.findAllByBookId(bookId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("未找到 bookId: " + bookId);
        }
        return rows;
    }
}
