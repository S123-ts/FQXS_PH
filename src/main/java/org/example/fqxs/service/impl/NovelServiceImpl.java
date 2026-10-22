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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NovelServiceImpl implements NovelService {

    /** “全部”视图默认过滤掉拉黑书籍，重新爬取不会恢复拉黑状态。 */
    private static final Set<String> WORD_RANGES = Set.of("100w以内", "100w以上", "200w以上");

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

    /** 查询失败一律外抛给 GlobalExceptionHandler：吞成空列表会让页面把“库挂了”读成“这里没书”。 */
    @Override
    public List<Novel> findAllBooksFiltered(List<String> categoryCodes, String wordRange, String status,
                                            boolean blockedOnly, String rankType) {
        List<Novel> novels = (categoryCodes == null || categoryCodes.isEmpty())
                ? novelRepository.findByRankType(rankType)
                : novelRepository.findByCategoryCodeInAndRankType(categoryCodes, rankType);

        List<Novel> result = new ArrayList<>(novels);
        if (wordRange != null && WORD_RANGES.contains(wordRange)) {
            result = result.stream()
                    .filter(novel -> matchesWordRange(novel.getWordCount(), wordRange))
                    .collect(Collectors.toList());
        }
        if (status != null && !status.isEmpty()) {
            result = result.stream()
                    .filter(novel -> status.equals(novel.getStatus()))
                    .collect(Collectors.toList());
        }
        result = result.stream()
                .filter(novel -> Boolean.TRUE.equals(novel.getIsBlocked()) == blockedOnly)
                .collect(Collectors.toList());

        if (blockedOnly) {
            // 后拉的黑排最前；这个字段之前拉黑的行没有时刻，退到末尾按热度排
            result.sort((a, b) -> {
                int byTime = compareBlockedTimeDesc(a.getBlockedTime(), b.getBlockedTime());
                return byTime != 0 ? byTime : Long.compare(raw(b.getReadCountRaw()), raw(a.getReadCountRaw()));
            });
        } else {
            result.sort((a, b) -> Long.compare(raw(b.getReadCountRaw()), raw(a.getReadCountRaw())));
        }
        return result;
    }

    @Override
    @Transactional
    public void setBlocked(String bookId, boolean blocked) {
        if (bookId == null || bookId.isBlank()) {
            throw new IllegalArgumentException("bookId 不能为空");
        }
        // 唯一键是 (categoryCode, bookId)，同一本书在每个分类榜各有一行，必须一起开关
        List<Novel> rows = novelRepository.findAllByBookId(bookId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("未找到 bookId: " + bookId);
        }
        for (Novel novel : rows) {
            novel.setIsBlocked(blocked);
            novel.setBlockedTime(blocked ? DateUtil.now() : null);
        }
        novelRepository.saveAll(rows);
        log.info("已{}书籍: {} (bookId={}, 涉及 {} 个分类行)",
                blocked ? "拉黑" : "解除拉黑", rows.get(0).getTitle(), bookId, rows.size());
    }

    /**
     * 免费引擎结论按 bookId 全行联动：同一本书在多个分类榜各有一行，
     * 结论必须一致，否则「全部」视图里同一本书会出现两种标记。
     */
    @Override
    @Transactional
    public void applyFreeVerdict(String bookId, int aiFlag, double aiScore, String engine) {
        if (bookId == null || bookId.isBlank()) {
            throw new IllegalArgumentException("bookId 不能为空");
        }
        List<Novel> rows = novelRepository.findAllByBookId(bookId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("未找到 bookId: " + bookId);
        }
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
        if (bookId == null || bookId.isBlank()) {
            throw new IllegalArgumentException("bookId 不能为空");
        }
        List<Novel> rows = novelRepository.findAllByBookId(bookId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("未找到 bookId: " + bookId);
        }
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
     * 把 U+E3E8–U+E555 画成汉字，所以页面「看着正常」，而库里的字节与接口返回都是残缺的。
     * 入库是唯一的文本写入口，在这里还原一次，之后搜索、导出、AI 提示词拿到的才是汉字。
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
        novel.setRankType(rankType);
    }

    private boolean matchesWordRange(String wordCount, String wordRange) {
        if (wordCount == null || wordCount.isEmpty()) {
            return false;
        }
        double wan = NovelTextParser.parseWordCountToWan(wordCount);
        return switch (wordRange) {
            case "100w以内" -> wan <= 100;
            case "100w以上" -> wan > 100;
            case "200w以上" -> wan > 200;
            default -> true;
        };
    }

    /** 新→旧；没有拉黑时刻的历史行排在所有有时刻的行之后。 */
    private int compareBlockedTimeDesc(LocalDateTime a, LocalDateTime b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        return b.compareTo(a);
    }

    private long raw(Long value) {
        return value == null ? 0L : value;
    }
}
