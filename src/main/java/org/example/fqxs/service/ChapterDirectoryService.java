package org.example.fqxs.service;

import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.client.ChapterClient;
import org.example.fqxs.client.ThirdPartyCatalogClient;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.logging.BookLogContext;
import org.example.fqxs.model.ChapterRef;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 目录取数的唯一入口：官方接口优先，失败才回退第三方 {@code /catalog}。
 *
 * <p>为什么要两条路：官方目录在约 340 次请求后会给出「200 + 空 body」的风控形态，
 * 而第三方线路走的是另一台机器（共享云手机），不会被同一套风控一起打死。回退只发生在
 * 官方<b>已经抛错</b>的时候，正常情况不额外增加上游密度。</p>
 *
 * <p>缓存分两层：官方目录由 {@link ChapterClient} 自己缓存（回退结果不该污染官方缓存，
 * 第三方线路随时可能被替换，缓存固化一次错误不值得），所以这里只缓存第三方那一份。</p>
 *
 * <p>回退会带来的唯一可见差异是 {@code locked}：第三方给的是 App 会员视角，几乎恒为 false，
 * 而官方给的是网页付费视角（一本 1928 章能标 1918 章）。这条线路的 {@code /content}
 * 确实取得到那些章，所以「显示可读」不是谎报，但别指望两边的锁章数量相等。</p>
 */
@Slf4j
@Service
public class ChapterDirectoryService {

    /** 回退目录的缓存上限：第三方一本 198KB，20 本约 4MB，够覆盖一次批量重跑。 */
    private static final int FALLBACK_CACHE_LIMIT = 20;

    private final ChapterClient chapterClient;
    private final ThirdPartyCatalogClient thirdPartyCatalogClient;
    private final UpstreamThrottle upstreamThrottle;

    /** bookId → 第三方回退目录。 */
    private final Map<String, List<ChapterRef>> fallbackCache =
            ChapterClient.lruCache(FALLBACK_CACHE_LIMIT);

    public ChapterDirectoryService(ChapterClient chapterClient,
                                   ThirdPartyCatalogClient thirdPartyCatalogClient,
                                   UpstreamThrottle upstreamThrottle) {
        this.chapterClient = chapterClient;
        this.thirdPartyCatalogClient = thirdPartyCatalogClient;
        this.upstreamThrottle = upstreamThrottle;
    }

    /**
     * 取整本书的结构化目录。
     *
     * @param bookId             书籍 ID
     * @param slotIntervalMillis 回源前的上游间隔；{@code <=0} 表示不限速
     * @return 有序章节列表
     * @throws CrawlException 官方失败且没有可用的第三方线路，或两边都失败
     */
    public List<ChapterRef> listChapters(String bookId, long slotIntervalMillis) {
        // 圈定这本书：官方目录与第三方目录回退的日志都归到它名下
        try (BookLogContext ignored = BookLogContext.open(bookId)) {
            List<ChapterRef> cachedFallback = fallbackCache.get(bookId);
            if (cachedFallback != null) {
                return cachedFallback;
            }
            if (chapterClient.hasCachedDirectory(bookId)) {
                // 缓存命中不回源，也就不能白占共享槽位
                return chapterClient.listChapters(bookId);
            }
            upstreamThrottle.awaitSlot(slotIntervalMillis);
            try {
                return chapterClient.listChapters(bookId);
            } catch (CrawlException officialFailure) {
                if (!thirdPartyCatalogClient.isUsable()) {
                    throw officialFailure;
                }
                upstreamThrottle.awaitSlot(slotIntervalMillis);
                try {
                    List<ChapterRef> chapters = thirdPartyCatalogClient.listChapters(bookId);
                    fallbackCache.put(bookId, chapters);
                    log.warn("官方目录失败，已改用第三方目录: bookId={}, 官方原因={}", bookId, officialFailure.getMessage());
                    return chapters;
                } catch (CrawlException fallbackFailure) {
                    throw new CrawlException("官方目录失败（" + officialFailure.getMessage()
                            + "），第三方目录也失败（" + fallbackFailure.getMessage() + "）", fallbackFailure);
                }
            }
        }
    }

    /**
     * 取整本书的有序章节 ID。
     *
     * @param bookId             书籍 ID
     * @param slotIntervalMillis 回源前的上游间隔
     * @return 章节 ID 列表
     */
    public List<String> listChapterIds(String bookId, long slotIntervalMillis) {
        return listChapters(bookId, slotIntervalMillis).stream().map(ChapterRef::itemId).toList();
    }

    /** 目录是否已在任一缓存里；调用方据此判断这次是不是真的会打上游。 */
    public boolean isCached(String bookId) {
        return fallbackCache.containsKey(bookId) || chapterClient.hasCachedDirectory(bookId);
    }
}
