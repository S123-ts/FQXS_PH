package org.example.fqxs.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.client.ChapterClient;
import org.example.fqxs.config.BookSourceConfig;
import org.example.fqxs.entity.Novel;
import org.example.fqxs.model.BookSourceView;
import org.example.fqxs.model.ChapterRef;
import org.example.fqxs.model.ChapterSample;
import org.example.fqxs.repository.NovelRepository;
import org.example.fqxs.util.PuaDecoder;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 书源适配层：把本地库与上游章节链路包装成 Legado「阅读」能直接消费的只读视图。
 *
 * <p>分层与项目约束一致：本类不碰 HTTP（URL 由 controller 按当前请求拼好传入），
 * 也不写 {@code novel_rank}——书源是纯读路径。目录与正文仍要打上游：正文在这里占
 * {@link UpstreamThrottle} 槽位，目录连同它的第三方回退一起由
 * {@link ChapterDirectoryService} 占槽，与 AI 判定共用同一条节拍。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookSourceService {

    /** 搜索关键词上限：再长也不是找书，只会拖慢全表 LIKE。 */
    static final int MAX_KEYWORD_LENGTH = 100;

    private final NovelRepository novelRepository;
    private final ChapterClient chapterClient;
    private final ChapterDirectoryService directoryService;
    private final BookSourceConfig bookSourceConfig;
    private final UpstreamThrottle upstreamThrottle;
    private final PuaDecoder puaDecoder;

    /**
     * 搜索本地库（书名/作者模糊匹配）。
     *
     * @param query   关键词；空串返回空结果，不把全库吐给调用方
     * @param page    页码，从 1 起
     * @param size    每页条数，钳到 [1, search-size-max]
     * @param baseUrl 当前服务的根地址，用于拼详情地址；为 null 时不返回 bookUrl
     * @return 当前页与真实命中总数
     */
    public SearchResult search(String query, int page, int size, String baseUrl) {
        String keyword = query == null ? "" : query.trim();
        if (keyword.isEmpty()) {
            return new SearchResult(List.of(), 0);
        }
        if (keyword.length() > MAX_KEYWORD_LENGTH) {
            throw new IllegalArgumentException("搜索关键词过长（上限 " + MAX_KEYWORD_LENGTH + " 字）");
        }
        String lower = keyword.toLowerCase(Locale.ROOT);
        String escaped = escapeLike(lower);
        String like = "%" + escaped + "%";
        String prefix = escaped + "%";
        int safeSize = Math.max(1, Math.min(size, bookSourceConfig.getSearchSizeMax()));
        int safePage = Math.max(1, page);

        List<Novel> rows = novelRepository.searchByKeyword(lower, like, prefix,
                PageRequest.of(safePage - 1, safeSize));
        long total = novelRepository.countSearchKeyword(like);
        /* 搜索只给列表字段 + bookUrl；status/lastChapter/tocUrl 留给详情，避免每条结果拖一串详情字段 */
        List<BookSourceView> items = rows.stream().map(novel -> toView(novel, baseUrl, false)).toList();
        return new SearchResult(items, total);
    }

    /**
     * 详情：只查本地库。库里没有就明确报「未收录」，<b>不回退上游</b>——
     * 上游详情依赖第三方共享云手机，可用性不受本服务控制。
     *
     * @param bookId  书籍 ID
     * @param baseUrl 当前服务的根地址，用于拼详情/目录完整链接
     * @return 书籍视图；本地库未收录时为空
     */
    public Optional<BookSourceView> findBook(String bookId, String baseUrl) {
        String id = requireId(bookId, "bookId");
        return novelRepository.findAllByBookId(id).stream()
                .min(Comparator.comparing(Novel::getId))
                .map(novel -> toView(novel, baseUrl, true));
    }

    /**
     * 目录：标题与分卷来自官方目录接口，官方失败时由 {@link ChapterDirectoryService}
     * 自动改走第三方 {@code /catalog}；锁章照常列出。
     *
     * @param bookId  书籍 ID
     * @param baseUrl 当前服务的根地址，用于拼正文完整链接
     * @return 目录视图
     */
    public DirectoryView directory(String bookId, String baseUrl) {
        String id = requireId(bookId, "bookId");
        List<Novel> rows = novelRepository.findAllByBookId(id);
        if (rows.isEmpty()) {
            // 书源只覆盖「上过榜的书」：未收录的书不该被拿来触发上游目录请求
            throw new IllegalArgumentException("本地库未收录该书");
        }
        /* 官方目录失败时这里会自己回退第三方 /catalog；缓存命中不回源也就不占共享槽位 */
        List<ChapterRef> chapters = directoryService.listChapters(id,
                bookSourceConfig.getRequestIntervalMillis());

        List<String> volumes = chapters.stream()
                .map(ChapterRef::volumeName)
                .filter(name -> name != null && !name.isBlank())
                .map(puaDecoder::decodeIfNeeded)
                .distinct()
                .toList();
        String contentBase = trimTrailingSlash(baseUrl) + "/api/bs/content?itemId=";
        List<DirectoryItem> items = chapters.stream()
                .map(chapter -> new DirectoryItem(
                        chapter.itemId(),
                        puaDecoder.decodeIfNeeded(chapter.title()),
                        puaDecoder.decodeIfNeeded(chapter.volumeName()),
                        withToken(contentBase
                                + URLEncoder.encode(chapter.itemId(), StandardCharsets.UTF_8)),
                        chapter.locked()))
                .toList();
        String bookName = rows.stream()
                .min(Comparator.comparing(Novel::getId))
                .map(Novel::getTitle)
                .map(puaDecoder::decodeIfNeeded)
                .orElse("");
        return new DirectoryView(id, bookName, volumes, items);
    }

    /**
     * 正文：直接复用官方 SSR 阅读页链路（已含 PUA 还原与空正文报错）。
     *
     * <p>锁章/风控都表现为空正文，{@link ChapterClient} 会抛 {@code CrawlException}，
     * 由全局异常处理器转成非 0 信封——<b>不伪装成空章</b>，否则阅读端会静默显示空白。</p>
     *
     * @param itemId 章节 ID
     * @return 章节视图
     */
    public ChapterView content(String itemId) {
        String id = requireId(itemId, "itemId");
        if (!chapterClient.hasCachedChapter(id)) {
            upstreamThrottle.awaitSlot(bookSourceConfig.getRequestIntervalMillis());
        }
        ChapterSample sample = chapterClient.fetchChapter(id);
        return new ChapterView(sample.itemId(), puaDecoder.decodeIfNeeded(sample.title()), sample.text());
    }

    private BookSourceView toView(Novel novel, String baseUrl, boolean detailed) {
        boolean hasBase = baseUrl != null && !baseUrl.isBlank();
        String base = trimTrailingSlash(baseUrl);
        String encodedId = URLEncoder.encode(String.valueOf(novel.getBookId()), StandardCharsets.UTF_8);
        String bookUrl = hasBase ? withToken(base + "/api/bs/info?bookId=" + encodedId) : null;
        String tocUrl = detailed && hasBase
                ? withToken(base + "/api/bs/directory?bookId=" + encodedId)
                : null;
        return new BookSourceView(
                novel.getBookId(),
                puaDecoder.decodeIfNeeded(novel.getTitle()),
                puaDecoder.decodeIfNeeded(novel.getAuthor()),
                novel.getThumbUri(),
                puaDecoder.decodeIfNeeded(novel.getDescription()),
                puaDecoder.decodeIfNeeded(novel.getCategoryName()),
                novel.getWordCount(),
                detailed ? novel.getStatus() : null,
                detailed ? puaDecoder.decodeIfNeeded(novel.getLastChapter()) : null,
                tocUrl,
                bookUrl);
    }

    /**
     * 给返回给阅读端的完整链接追加书源令牌。
     *
     * <p>Legado 会直接请求目录里的绝对 URL，不会自动补 bookSourceUrl 的查询参数；
     * 令牌只在拦截器里校验是不够的，必须让后续 info/directory/content 链接自己带上。
     * 令牌留空时原样返回（内网自用）。</p>
     */
    private String withToken(String url) {
        String token = bookSourceConfig.getToken();
        if (token == null || token.isBlank()) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + "token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    /** 通配符转义：调用方传 `%`/`_` 只当普通字符，避免一次模糊搜索退化成全表扫描。 */
    static String escapeLike(String keyword) {
        return keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static String requireId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }

    private static String trimTrailingSlash(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** 搜索页：当前页数据 + 真实命中总数。 */
    public record SearchResult(List<BookSourceView> items, long total) {
    }

    /** 目录页：卷名清单（保序去重）与章节项。 */
    public record DirectoryView(String bookId, String bookName, List<String> volumes,
                                List<DirectoryItem> items) {
    }

    /**
     * 目录项。
     *
     * @param itemId 章节 ID
     * @param title  章节标题
     * @param volume 所属卷名
     * @param url    正文完整地址（不让阅读端自己拼相对路径）
     * @param locked 是否锁章/付费章
     */
    public record DirectoryItem(String itemId, String title, String volume, String url, boolean locked) {
    }

    /** 正文页。 */
    public record ChapterView(String itemId, String title, String content) {
    }
}
