package org.example.fqxs.client;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.config.FanqieConfig;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.model.ChapterSample;
import org.example.fqxs.util.PuaDecoder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 章节正文采集：目录走 JSON 接口，正文走服务端渲染的阅读页。
 *
 * <p>正文的 JSON 接口（{@code /api/reader/full}）实测返回 200 但 body 为空——这是上游风控的表现，
 * 不能当“空章节”处理。阅读页 {@code /reader/{itemId}} 把正文放在
 * {@code window.__INITIAL_STATE__.reader.chapterData} 里，只需浏览器 UA 与同页 Referer。</p>
 */
@Slf4j
@Component
public class ChapterClient {

    /** 上游 Web 端 UA：非浏览器 UA 会被直接拦成空响应。第三方正文通道也用同一个。 */
    static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private static final String STATE_MARKER = "window.__INITIAL_STATE__";

    private static final String DIRECTORY_API = "/api/reader/directory/detail";

    /** 目录缓存上限：同一本书反复初筛/逐章校验时省掉重复的目录请求。 */
    private static final int DIRECTORY_CACHE_LIMIT = 200;

    /** 对 429 与 5xx 的重试次数上限（不含首次）。 */
    private static final int MAX_RETRIES = 1;

    /** 重试前的退避间隔。 */
    private static final long RETRY_BACKOFF_MILLIS = 2000;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final FanqieConfig fanqieConfig;
    private final AiDetectConfig aiDetectConfig;
    private final PuaDecoder puaDecoder;

    /** bookId → 有序章节 ID；章节顺序就是阅读顺序，抽章必须按它取。 */
    private final Map<String, List<String>> directoryCache = lruCache(DIRECTORY_CACHE_LIMIT);

    /** itemId → 章节样本；正文抓取走 SSR 阅读页较慢，缓存让交叉判定不再重复打上游。 */
    private final Map<String, ChapterSample> chapterCache;

    /**
     * 建一个访问序 LRU 缓存：超限淘汰最久未用条目，替代原先「满即整体 clear」的粗暴策略。
     *
     * @param limit 最大条目数
     * @return 线程安全的 LRU Map
     */
    static <K, V> Map<K, V> lruCache(int limit) {
        return Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > limit;
            }
        });
    }

    public ChapterClient(OkHttpClient httpClient, ObjectMapper objectMapper, FanqieConfig fanqieConfig,
                         AiDetectConfig aiDetectConfig, PuaDecoder puaDecoder) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.fanqieConfig = fanqieConfig;
        this.aiDetectConfig = aiDetectConfig;
        this.puaDecoder = puaDecoder;
        this.chapterCache = lruCache(Math.max(0, aiDetectConfig.getChapterCacheSize()));
    }

    /**
     * 取整本书的有序章节 ID。
     *
     * @param bookId 书籍 ID
     * @return 章节 ID 列表，顺序即正文章节顺序
     * @throws CrawlException 上游不可用或返回空目录（风控）
     */
    public List<String> listChapterIds(String bookId) {
        if (bookId == null || bookId.isBlank()) {
            throw new IllegalArgumentException("bookId 不能为空");
        }
        List<String> cachedIds = directoryCache.get(bookId);
        if (cachedIds != null) {
            return cachedIds;
        }
        String url = HttpUrl.get(fanqieConfig.getBaseUrl() + DIRECTORY_API).newBuilder()
                .addQueryParameter("bookId", bookId)
                .build()
                .toString();
        JsonNode root = executeJson(url, fanqieConfig.getBaseUrl() + "/page/" + bookId, "章节目录");
        JsonNode itemIdsNode = root.path("data").path("allItemIds");

        List<String> itemIds = new ArrayList<>();
        if (itemIdsNode.isArray()) {
            itemIdsNode.forEach(node -> {
                if (node.isTextual() && !node.asText().isBlank()) {
                    itemIds.add(node.asText());
                }
            });
        }
        final List<String> result = List.copyOf(itemIds);
        if (result.isEmpty()) {
            // 上游风控与“这本书真没有章节”都会给空数组，一律按失败处理，避免把书判成没有正文
            throw new CrawlException("章节目录为空（bookId=" + bookId + "），可能被上游风控");
        }

        directoryCache.put(bookId, result);
        log.info("章节目录获取成功: bookId={}, 章节数={}", bookId, result.size());
        return result;
    }

    /**
     * 抓取并还原单章正文。
     *
     * @param itemId 章节 ID
     * @return 章节样本（正文已去标签、已做 PUA 还原）
     * @throws CrawlException 页面结构变化、被风控或正文为空
     */
    public ChapterSample fetchChapter(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId 不能为空");
        }
        ChapterSample cached = chapterCache.get(itemId);
        if (cached != null) {
            return cached;
        }
        String pageUrl = fanqieConfig.getBaseUrl() + "/reader/" + itemId;
        String html = executeText(pageUrl, pageUrl, "章节正文");

        JsonNode chapterData = parseChapterData(html, itemId);
        String rawContent = chapterData.path("content").asText("");
        if (rawContent.isBlank()) {
            throw new CrawlException("章节正文为空（itemId=" + itemId + "），可能被上游风控");
        }

        String plainText = puaDecoder.decode(htmlToPlainText(rawContent));
        if (plainText.isBlank()) {
            throw new CrawlException("章节正文解析后为空（itemId=" + itemId + "）");
        }
        ChapterSample sample = new ChapterSample(itemId, chapterData.path("title").asText(""), plainText, plainText.length());
        chapterCache.put(itemId, sample);
        return sample;
    }

    /**
     * 从阅读页 HTML 中取出 chapterData 节点。
     *
     * @param html   页面源码
     * @param itemId 章节 ID，仅用于错误信息
     * @return chapterData 节点
     */
    private JsonNode parseChapterData(String html, String itemId) {
        int markerIndex = html.indexOf(STATE_MARKER);
        if (markerIndex < 0) {
            throw new CrawlException("阅读页缺少初始化状态（itemId=" + itemId + "），页面结构可能已变");
        }
        int objectStart = html.indexOf('{', markerIndex);
        if (objectStart < 0) {
            throw new CrawlException("阅读页初始化状态格式异常（itemId=" + itemId + "）");
        }
        // 从 JSON 起点开始增量解析：只读到第一个完整对象就停，后面紧跟的 </script> 不参与解析
        try (JsonParser parser = objectMapper.getFactory().createParser(html.substring(objectStart))) {
            JsonNode root = objectMapper.readTree(parser);
            JsonNode chapterData = root.path("reader").path("chapterData");
            if (chapterData.isMissingNode() || chapterData.isEmpty()) {
                throw new CrawlException("阅读页没有章节数据（itemId=" + itemId + "）");
            }
            return chapterData;
        } catch (IOException ioException) {
            throw new CrawlException("阅读页初始化状态解析失败（itemId=" + itemId + "）", ioException);
        }
    }

    /**
     * 把上游的 HTML 正文转成纯文本：段落标签转换行，其余标签剥掉，实体反转义。
     *
     * <p>包级静态：第三方正文通道的还原口径必须与这里<b>完全一致</b>，否则两路文本没法比，
     * 所以直接共用这一份实现而不是复制。</p>
     *
     * @param htmlContent 上游 content 字段
     * @return 纯文本
     */
    static String htmlToPlainText(String htmlContent) {
        String text = htmlContent
                .replaceAll("(?i)</p\\s*>", "\n")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return text.replaceAll("\n{2,}", "\n").trim();
    }

    /**
     * 请求并解析 JSON。
     *
     * @param url     目标地址
     * @param referer 上游要求的来源页
     * @param scene   日志用的场景名
     * @return 根节点
     */
    private JsonNode executeJson(String url, String referer, String scene) {
        String body = executeText(url, referer, scene);
        try {
            return objectMapper.readTree(body);
        } catch (IOException ioException) {
            throw new CrawlException(scene + "返回的不是合法 JSON", ioException);
        }
    }

    /**
     * 发起带浏览器请求头的 GET，对 429/5xx/IO 失败重试一次（退避 2 秒）。
     *
     * @param url     目标地址
     * @param referer 上游要求的来源页
     * @param scene   日志用的场景名
     * @return 响应体文本
     */
    private String executeText(String url, String referer, String scene) {
        CrawlException lastFailure = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return executeTextOnce(url, referer, scene);
            } catch (CrawlException failure) {
                lastFailure = failure;
                if (attempt >= MAX_RETRIES || !isRetryable(failure.getMessage())) {
                    throw failure;
                }
                log.warn("{}请求失败将重试（第 {} 次）: {}", scene, attempt + 1, failure.getMessage());
                sleepBeforeRetry();
            }
        }
        throw lastFailure;
    }

    /** 429 与 5xx 值得再试一次（限流/瞬时故障）；403、风控空响应重试也没用。 */
    private static final Pattern RETRYABLE_CODE = Pattern.compile("上游返回 (?:429|5\\d\\d)");

    private boolean isRetryable(String message) {
        if (message == null) {
            return false;
        }
        if (RETRYABLE_CODE.matcher(message).find()) {
            return true;
        }
        String lower = message.toLowerCase();
        return lower.contains("timed out") || lower.contains("timeout") || lower.contains("connection");
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_BACKOFF_MILLIS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 发起单次带浏览器请求头的 GET。
     *
     * @param url     目标地址
     * @param referer 上游要求的来源页
     * @param scene   日志用的场景名
     * @return 响应体文本
     */
    private String executeTextOnce(String url, String referer, String scene) {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/json,*/*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Referer", referer)
                .build();
        long start = System.currentTimeMillis();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new CrawlException(scene + "抓取失败: 上游返回 " + response.code());
            }
            ResponseBody responseBody = response.body();
            String body = responseBody == null ? "" : new String(responseBody.bytes(), StandardCharsets.UTF_8);
            if (body.isBlank()) {
                // 200 + 空 body 是上游风控的典型形态，不能当正常空内容
                throw new CrawlException(scene + "返回空响应，可能被上游风控");
            }
            log.debug("{}抓取完成: {} 字符, 耗时 {} ms", scene, body.length(), System.currentTimeMillis() - start);
            return body;
        } catch (IOException ioException) {
            throw new CrawlException(scene + "抓取失败: " + ioException.getMessage(), ioException);
        }
    }
}
