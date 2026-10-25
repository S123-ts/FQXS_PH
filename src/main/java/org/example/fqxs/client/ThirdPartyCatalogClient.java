package org.example.fqxs.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.model.ChapterRef;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 第三方章节目录：官方 {@code /api/reader/directory/detail} 被风控时的第二条路。
 *
 * <p>2026-10-24 实测《我不是戏神》{@code GET /catalog?book_id=} 返回 1928 章、198KB，
 * 与官方目录同为一份上游数据：{@code item_data_list} 每章带
 * {@code item_id / title / volume_name / need_unlock / chapter_word_number}，
 * 标题同样是干净中文（不做 PUA 混淆），可以直接对齐 {@link ChapterRef}。</p>
 *
 * <p>{@code catalog_data} 是精简版（只有 {@code catalog_id / catalog_title / item_id}），
 * 万一上游哪天只给这一块就按它兜底，卷名留空。</p>
 *
 * <p><b>锁章口径与官方不同</b>：{@code need_unlock} 是 App 会员视角，2026-10-24 实测《我不是戏神》
 * 1928 章全为 false，而官方 Web 目录同一本书有 1918 章标 {@code needPay}。回退后 {@code locked}
 * 会整体变松——这与实际能力一致（这条线路的 {@code /content} 确实取得到那些章），
 * 但读目录响应时别把两边的 locked 数量当同一个指标比。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ThirdPartyCatalogClient {

    /** 第三方参数名是下划线 {@code book_id}。 */
    private static final String CATALOG_API = "/catalog";

    private final ObjectMapper objectMapper;
    private final ThirdPartySources sources;

    /**
     * 取整本书的结构化目录。
     *
     * @param bookId 书籍 ID
     * @return 有序章节列表
     * @throws CrawlException 没有可用线路，或所有线路都返回坏形态/空目录
     */
    public List<ChapterRef> listChapters(String bookId) {
        if (bookId == null || bookId.isBlank()) {
            throw new IllegalArgumentException("bookId 不能为空");
        }
        return sources.fetch(CATALOG_API, "book_id", bookId, "第三方目录", bookId,
                body -> parseCatalog(body, bookId));
    }

    /** 是否还有线路可试；官方目录失败时上层据此决定要不要合并两边的失败原因。 */
    public boolean isUsable() {
        return sources.hasUsable();
    }

    /**
     * 解析 {@code /catalog} 响应。包级可见：坏形态靠单测覆盖，不去撞真实线路。
     *
     * @param body   响应体
     * @param bookId 书籍 ID，仅用于错误信息
     * @return 有序章节列表
     * @throws CrawlException 非 JSON、data 是堆栈字符串、code 非 0 或章节列表为空
     */
    List<ChapterRef> parseCatalog(String body, String bookId) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException ioException) {
            throw new CrawlException("第三方目录返回的不是合法 JSON（bookId=" + bookId + "）", ioException);
        }
        JsonNode envelope = root.path("data");
        if (envelope.isTextual()) {
            throw new CrawlException("第三方目录异常: " + abbreviate(envelope.asText()));
        }
        if (!"0".equals(envelope.path("code").asText(""))) {
            throw new CrawlException("第三方目录返回 code=" + envelope.path("code").asText("")
                    + "（bookId=" + bookId + "）");
        }
        JsonNode data = envelope.path("data");
        List<ChapterRef> chapters = toItemDataList(data.path("item_data_list"));
        if (chapters.isEmpty()) {
            chapters = toCatalogData(data.path("catalog_data"));
        }
        if (chapters.isEmpty()) {
            // 空目录与「这本书真没章节」无法区分，一律按线路故障处理，交给上层换源或报错
            throw new CrawlException("第三方目录为空（bookId=" + bookId + "）");
        }
        log.info("第三方目录获取成功: bookId={}, 章节数={}", bookId, chapters.size());
        return List.copyOf(chapters);
    }

    /** 首选形态：每章带标题与卷名。 */
    private static List<ChapterRef> toItemDataList(JsonNode items) {
        if (!items.isArray()) {
            return List.of();
        }
        List<ChapterRef> chapters = new ArrayList<>();
        for (JsonNode item : items) {
            String itemId = item.path("item_id").asText("");
            if (itemId.isBlank()) {
                continue;
            }
            chapters.add(new ChapterRef(itemId, item.path("title").asText(""),
                    item.path("volume_name").asText(""), item.path("need_unlock").asBoolean(false)));
        }
        return chapters;
    }

    /** 兜底形态：只有 {@code catalog_title}，卷名留空。 */
    private static List<ChapterRef> toCatalogData(JsonNode items) {
        if (!items.isArray()) {
            return List.of();
        }
        List<ChapterRef> chapters = new ArrayList<>();
        for (JsonNode item : items) {
            String itemId = item.path("item_id").asText("");
            if (itemId.isBlank()) {
                continue;
            }
            chapters.add(new ChapterRef(itemId, item.path("catalog_title").asText(""), "",
                    item.path("need_unlock").asBoolean(false)));
        }
        return chapters;
    }

    private static String abbreviate(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ");
        return flat.length() > 90 ? flat.substring(0, 90) + "…" : flat;
    }
}
