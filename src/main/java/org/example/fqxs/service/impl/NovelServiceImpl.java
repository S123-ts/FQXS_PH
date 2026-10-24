package org.example.fqxs.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.constant.CategoryCatalog;
import org.example.fqxs.entity.Novel;
import org.example.fqxs.model.NovelInfo;
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
    private final PuaDecoder puaDecoder;

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
                                            int page, int size, String authorGender) {
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
        if (categoryCodes == null || categoryCodes.isEmpty()) {
            return novelRepository.pageAllBooks(rankType, blockedOnly, watchedOnly,
                    statusFilter, minWan, maxWan, authorFilter, pageable);
        }
        return novelRepository.pageAllBooksByCodes(categoryCodes, rankType, blockedOnly, watchedOnly,
                statusFilter, minWan, maxWan, authorFilter, pageable);
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
     * 免费引擎结论按 bookId 全行联动：同一本书在多个分类榜各有一行，
     * 结论必须一致，否则「全部」视图里同一本书会出现两种标记。
     */
    @Override
    @Transactional
    public void applyFreeVerdict(String bookId, int aiFlag, double aiScore, String engine) {
        List<Novel> rows = requireRows(bookId);
        // 1. 逐行写入同一份结论与时刻，保证跨分类展示一致
        LocalDateTime checkedTime = DateUtil.now();
        for (Novel novel : rows) {
            novel.setAiFlag(aiFlag);
            novel.setAiScore(aiScore);
            novel.setAiEngine(engine);
            novel.setAiCheckedTime(checkedTime);
        }
        novelRepository.saveAll(rows);
        log.info("免费判定已落库: bookId={}, 标记={}, 概率={}, 涉及 {} 行", bookId, aiFlag, aiScore, rows.size());
    }

    /**
     * 手动逐章校验结论按 bookId 全行联动。命中 AI 时覆盖 ai_flag：
     * 手动校验的结论优先于整本初筛的判别结果。
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
            // 2. 命中即覆盖总标记：手动校验（无论哪个引擎）优先级最高
            if (aiHit) {
                novel.setAiFlag(2);
                novel.setAiScore(manualScore);
                novel.setAiEngine(engine);
                novel.setAiCheckedTime(checkedTime);
            }
        }
        novelRepository.saveAll(rows);
        log.info("手动校验已落库: bookId={}, engine={}, 标记={}, 已查 {} 章, 风险占比={}",
                bookId, engine, manualFlag, checkedChapters, manualScore);
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
