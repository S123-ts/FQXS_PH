package org.example.fqxs.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.model.ChapterSample;
import org.example.fqxs.util.PuaDecoder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 第三方正文通道：补官方未登录取不到的会员章节（第 11 章起官方只有 150–180 字试读）。
 *
 * <p>S1 一致性实测（2026-10-22）：与官方 SSR 阅读页相比，两路正文去掉空白后<b>逐字相同</b>
 * （2057 字、公共前缀 100%），第三方只是每段多一个全角空格，且同样返回 PUA 混淆串。
 * 所以还原口径直接复用 {@link ChapterClient#htmlToPlainText} 与同一个 {@link PuaDecoder}，
 * 不复制第二份实现——两份口径迟早会漂。</p>
 *
 * <p>这条线路跑在别人的共享云手机上（内网穿透地址按天变），三种故障形态都要防：连不上、
 * 非 2xx、以及最阴的一种——HTTP 200 且 {@code isSuccess:true}，但 {@code data} 里装着
 * Xposed 堆栈字符串。因此判成功的唯一依据是<b>解析出非空正文</b>，不看状态位；
 * 结构性失败即进入冷却，不让后续每本书都白试一次。</p>
 */
@Slf4j
@Component
public class ThirdPartyChapterClient {

    /** 参数名是下划线 {@code item_id}：与官方接口的驼峰 {@code bookId} 相反，写错会被回「参数不能为空」。 */
    private static final String CONTENT_API = "/content";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AiDetectConfig aiDetectConfig;
    private final PuaDecoder puaDecoder;

    /**
     * 独立缓存：同一个 itemId 在官方那路只有试读、在这路是全文，
     * 与 ChapterClient 共用缓存会让两种长度互相遮蔽，所以各存一份。
     */
    private final Map<String, ChapterSample> chapterCache;

    /** 冷却截止时间戳：未到该时刻前 {@link #isUsable()} 返回 false。 */
    private final AtomicLong unavailableUntilMillis = new AtomicLong();

    public ThirdPartyChapterClient(OkHttpClient httpClient, ObjectMapper objectMapper,
                                   AiDetectConfig aiDetectConfig, PuaDecoder puaDecoder) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.aiDetectConfig = aiDetectConfig;
        this.puaDecoder = puaDecoder;
        this.chapterCache = ChapterClient.lruCache(Math.max(0, aiDetectConfig.getChapterCacheSize()));
    }

    /** 通道是否还值得试：开关开着且不在冷却期。取样器据此决定深章走哪一路。 */
    public boolean isUsable() {
        return aiDetectConfig.isThirdPartyEnabled()
                && System.currentTimeMillis() >= unavailableUntilMillis.get();
    }

    /**
     * 取一章会员全文。
     *
     * @param itemId 章节 ID
     * @return 章节样本（已去标签、已 PUA 还原）
     * @throws CrawlException 连不上、非 2xx，或响应里根本没有可用正文
     */
    public ChapterSample fetchChapter(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId 不能为空");
        }
        ChapterSample cached = chapterCache.get(itemId);
        if (cached != null) {
            return cached;
        }
        ChapterSample sample = parseSample(execute(itemId), itemId);
        chapterCache.put(itemId, sample);
        return sample;
    }

    /**
     * 解析 {@code /content} 响应。包级可见是因为坏形态只能靠它做单测，不该真连上游去撞。
     *
     * @param body   响应体
     * @param itemId 章节 ID，仅用于错误信息
     * @return 章节样本
     */
    ChapterSample parseSample(String body, String itemId) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException ioException) {
            markUnavailable("响应不是合法 JSON");
            throw new CrawlException("第三方正文返回的不是合法 JSON（itemId=" + itemId + "）", ioException);
        }
        JsonNode dataNode = root.path("data");
        if (dataNode.isTextual()) {
            // 故障时 data 变成堆栈字符串，而 isSuccess 依然是 true：按形态判，不按状态位判
            markUnavailable("data 是异常字符串");
            throw new CrawlException("第三方正文异常: " + abbreviate(dataNode.asText()));
        }
        JsonNode chapterData = dataNode.path("data");
        String rawContent = chapterData.path("content").asText("");
        if (rawContent.isBlank()) {
            markUnavailable("正文为空");
            throw new CrawlException("第三方正文为空（itemId=" + itemId + "），上游共享云手机可能已断开");
        }
        String plainText = puaDecoder.decode(ChapterClient.htmlToPlainText(rawContent));
        if (plainText.isBlank()) {
            markUnavailable("正文还原后为空");
            throw new CrawlException("第三方正文解析后为空（itemId=" + itemId + "）");
        }
        return new ChapterSample(itemId, chapterData.path("title").asText(""), plainText, plainText.length());
    }

    /**
     * 发一次请求。共享线路不做重试：它挂的时候重试只会加压，失败让冷却去处理。
     *
     * @param itemId 章节 ID
     * @return 响应体文本
     */
    private String execute(String itemId) {
        String url = HttpUrl.get(aiDetectConfig.getThirdPartyBaseUrl() + CONTENT_API).newBuilder()
                .addQueryParameter("item_id", itemId)
                .build()
                .toString();
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", ChapterClient.USER_AGENT)
                .header("Accept", "application/json,*/*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                markUnavailable("上游返回 " + response.code());
                throw new CrawlException("第三方正文抓取失败: 上游返回 " + response.code());
            }
            ResponseBody responseBody = response.body();
            String body = responseBody == null ? "" : new String(responseBody.bytes(), StandardCharsets.UTF_8);
            if (body.isBlank()) {
                markUnavailable("空响应");
                throw new CrawlException("第三方正文返回空响应");
            }
            return body;
        } catch (IOException ioException) {
            markUnavailable("请求失败");
            throw new CrawlException("第三方正文抓取失败: " + ioException.getMessage(), ioException);
        }
    }

    /** 记一次结构性失败并冷却；已经在冷却期内就不重复刷屏。 */
    private void markUnavailable(String reason) {
        int cooldown = Math.max(0, aiDetectConfig.getThirdPartyCooldownSeconds());
        long now = System.currentTimeMillis();
        if (unavailableUntilMillis.getAndSet(now + cooldown * 1000L) <= now) {
            log.warn("第三方正文通道进入冷却 {} 秒: {}", cooldown, reason);
        }
    }

    private static String abbreviate(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ");
        return flat.length() > 90 ? flat.substring(0, 90) + "…" : flat;
    }
}
