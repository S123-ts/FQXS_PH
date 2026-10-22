package org.example.fqxs.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.client.ChapterClient;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.entity.NovelChapter;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.model.ChapterSample;
import org.example.fqxs.repository.NovelChapterRepository;
import org.example.fqxs.util.ChapterTextCleaner;
import org.example.fqxs.util.DateUtil;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 章节正文的「库优先」取章入口。
 *
 * <p>默认取样（前 10 章）走这里：<b>库里有就直接用，没有才回源抓官方</b>，抓到后清洗并落库。
 * 一本书被不同引擎反复判定、批量初筛一口气判 5 本、或者详情里回看，都不会再重复打上游——
 * 这正是 800ms 全局节拍下最值得省的那部分请求。</p>
 *
 * <p>写入失败<b>不让判定失败</b>：章节表是缓存，不是判定链路的一环。库不可用时照常返回正文，
 * 只留一条 WARN。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterContentService {

    private final NovelChapterRepository chapterRepository;
    private final ChapterClient chapterClient;
    private final ChapterTextCleaner chapterTextCleaner;
    private final AiDetectConfig aiDetectConfig;

    /**
     * 取一章正文：库优先，未命中回源并写库。
     *
     * @param bookId       书籍 ID（落库要存）
     * @param itemId       章节 ID
     * @param chapterIndex 全书章号（0-based）；缺失时传 -1，落库时按 0 处理
     * @return 章节样本（已清洗）
     * @throws CrawlException 回源失败，或正文为空
     */
    public ChapterSample getOrFetch(String bookId, String itemId, int chapterIndex) {
        return getOrFetch(bookId, itemId, chapterIndex, null);
    }

    /**
     * 取一章正文，并在<b>确实要回源之前</b>执行一次上游节流回调。
     *
     * <p>节流必须是"挑在回源那一刻"而不是"进方法就等"：库命中率越高，白等的次数越多。
     * 一本书判第二次时 10 章全部命中，等待时间应当为 0。</p>
     *
     * @param beforeUpstream 回源前的回调（通常占一个 800ms 上游槽），可为 null
     */
    public ChapterSample getOrFetch(String bookId, String itemId, int chapterIndex,
                                    Runnable beforeUpstream) {
        Optional<ChapterSample> cached = findCached(itemId);
        if (cached.isPresent()) {
            return cached.get();
        }
        if (beforeUpstream != null) {
            beforeUpstream.run();
        }
        ChapterSample fetched = chapterClient.fetchChapter(itemId);
        ChapterTextCleaner.Cleaned cleaned = chapterTextCleaner.clean(fetched.text(), fetched.title());
        if (cleaned.isEmpty()) {
            // 空正文先前已被 ChapterClient 拦成异常，这里再兜一层：清洗后为空同样不能用
            throw new CrawlException("章节正文为空（清洗后）: itemId=" + itemId);
        }
        store(bookId, itemId, chapterIndex, fetched.title(), cleaned);
        return new ChapterSample(itemId, fetched.title(), cleaned.text(), cleaned.chars());
    }

    /**
     * 只查库，不触发任何上游请求。
     *
     * @param itemId 章节 ID
     * @return 命中时返回章节样本；库关闭或未命中返回空
     */
    public Optional<ChapterSample> findCached(String itemId) {
        if (itemId == null || itemId.isBlank() || !aiDetectConfig.isChapterStoreEnabled()) {
            return Optional.empty();
        }
        try {
            return chapterRepository.findByItemId(itemId)
                    .filter(row -> row.getContent() != null && !row.getContent().isBlank())
                    .map(row -> new ChapterSample(row.getItemId(),
                            row.getChapterTitle(),
                            row.getContent(),
                            row.getCharCount() == null ? row.getContent().length() : row.getCharCount()));
        } catch (RuntimeException databaseFailure) {
            // 章节表不可用（库锁、迁移未跑）时退化成"没命中"，不阻断判定
            log.warn("章节库查询失败，本次回源抓取: itemId={}, 原因={}", itemId, databaseFailure.getMessage());
            return Optional.empty();
        }
    }

    /** 某本书已落库的章数：诊断与验收用。 */
    public long countByBook(String bookId) {
        return chapterRepository.countByBookId(bookId);
    }

    /**
     * 写入缓存。内容超列宽时截断（不丢整章），并记下 PUA 残留量。
     */
    private void store(String bookId, String itemId, int chapterIndex, String title,
                       ChapterTextCleaner.Cleaned cleaned) {
        if (!aiDetectConfig.isChapterStoreEnabled()) {
            return;
        }
        String content = cleaned.text();
        if (content.length() > NovelChapter.CONTENT_MAX_LENGTH) {
            log.warn("章节正文超列宽，已截断: itemId={}, 原长度={}", itemId, content.length());
            content = content.substring(0, NovelChapter.CONTENT_MAX_LENGTH);
        }
        if (cleaned.puaUnresolved() > 0) {
            // 映射表过期的唯一信号：正常恒为 0，非 0 说明有码位没还原出来
            log.warn("章节正文解码后仍残留 {} 个私用区字符，映射表可能已过期: bookId={}, itemId={}",
                    cleaned.puaUnresolved(), bookId, itemId);
        }
        try {
            Optional<NovelChapter> existing = chapterRepository.findByItemId(itemId);
            NovelChapter row = existing.orElseGet(NovelChapter::new);
            LocalDateTime now = DateUtil.now();
            row.setBookId(bookId);
            row.setItemId(itemId);
            row.setChapterIndex(Math.max(chapterIndex, 0));
            row.setChapterTitle(title);
            row.setContent(content);
            row.setCharCount(content.length());
            row.setPuaUnresolved(cleaned.puaUnresolved());
            row.setSource(NovelChapter.SOURCE_OFFICIAL);
            if (row.getFetchedTime() == null) {
                row.setFetchedTime(now);
            }
            row.setUpdatedTime(now);
            chapterRepository.saveAndFlush(row);
        } catch (DataIntegrityViolationException conflict) {
            // 另一个线程刚写了同一章：唯一键兜住了，本次直接放弃写入（内容等价）
            log.debug("章节缓存并发写入冲突，忽略: itemId={}", itemId);
        } catch (RuntimeException databaseFailure) {
            log.warn("章节缓存写入失败，判定继续: itemId={}, 原因={}", itemId, databaseFailure.getMessage());
        }
    }
}
