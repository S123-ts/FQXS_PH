package org.example.fqxs.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.model.ChapterSample;
import org.example.fqxs.util.PuaDecoder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * 第三方正文通道：补官方未登录取不到的会员章节（第 11 章起官方只有 150–180 字试读）。
 *
 * <p>与官方 SSR 阅读页相比，两路正文去掉空白后<b>逐字相同</b>（2057 字、公共前缀 100%），
 * 第三方只是每段多一个全角空格，且同样返回 PUA 混淆串。因此还原口径直接复用
 * {@link ChapterClient#htmlToPlainText} 与同一个 {@link PuaDecoder}，不复制第二份实现，
 * 避免两套口径漂移。</p>
 *
 * <p>线路本身（多源、冷却、故障切换）交给 {@link ThirdPartySources}，本类只管单条响应的解析。</p>
 */
@Slf4j
@Component
public class ThirdPartyChapterClient {

    /** 参数名是下划线 {@code item_id}：与官方接口的驼峰 {@code bookId} 相反，写错会被回「参数不能为空」。 */
    private static final String CONTENT_API = "/content";

    private final ObjectMapper objectMapper;
    private final PuaDecoder puaDecoder;
    private final ThirdPartySources sources;

    /**
     * 独立缓存：同一个 itemId 在官方那路只有试读、在这路是全文，
     * 与 ChapterClient 共用缓存会让两种长度互相遮蔽，所以各存一份。
     */
    private final Map<String, ChapterSample> chapterCache;

    public ThirdPartyChapterClient(ObjectMapper objectMapper, PuaDecoder puaDecoder,
                                   AiDetectConfig aiDetectConfig, ThirdPartySources sources) {
        this.objectMapper = objectMapper;
        this.puaDecoder = puaDecoder;
        this.sources = sources;
        this.chapterCache = ChapterClient.lruCache(Math.max(0, aiDetectConfig.getChapterCacheSize()));
    }

    /** 通道是否还值得试：至少有一条线路没在冷却。取样器据此决定深章走哪一路。 */
    public boolean isUsable() {
        return sources.hasUsable();
    }

    /**
     * 取一章会员全文。
     *
     * @param itemId 章节 ID
     * @return 章节样本（已去标签、已 PUA 还原）
     * @throws CrawlException 没有可用线路，或所有线路都取不到可用正文
     */
    public ChapterSample fetchChapter(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId 不能为空");
        }
        ChapterSample cached = chapterCache.get(itemId);
        if (cached != null) {
            return cached;
        }
        ChapterSample sample = sources.fetch(CONTENT_API, "item_id", itemId, "第三方正文",
                body -> parseSample(body, itemId));
        chapterCache.put(itemId, sample);
        return sample;
    }

    /**
     * 解析 {@code /content} 响应。包级可见是因为坏形态只能靠它做单测，不该真连上游去撞。
     *
     * <p>解析失败抛 {@link CrawlException} 即为「这条线路有问题」的信号，由
     * {@link ThirdPartySources} 冷却这条线并换下一条。</p>
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
            throw new CrawlException("第三方正文返回的不是合法 JSON（itemId=" + itemId + "）", ioException);
        }
        JsonNode dataNode = root.path("data");
        if (dataNode.isTextual()) {
            // 故障时 data 变成堆栈字符串，而 isSuccess 依然是 true：按形态判，不按状态位判
            throw new CrawlException("第三方正文异常: " + abbreviate(dataNode.asText()));
        }
        JsonNode chapterData = dataNode.path("data");
        String rawContent = chapterData.path("content").asText("");
        if (rawContent.isBlank()) {
            throw new CrawlException("第三方正文为空（itemId=" + itemId + "），上游共享云手机可能已断开");
        }
        String plainText = puaDecoder.decode(ChapterClient.htmlToPlainText(rawContent));
        if (plainText.isBlank()) {
            throw new CrawlException("第三方正文解析后为空（itemId=" + itemId + "）");
        }
        return new ChapterSample(itemId, chapterData.path("title").asText(""), plainText, plainText.length());
    }

    private static String abbreviate(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ");
        return flat.length() > 90 ? flat.substring(0, 90) + "…" : flat;
    }
}
