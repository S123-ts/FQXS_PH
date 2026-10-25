package org.example.fqxs.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.exception.ItemUnavailableException;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 第三方书籍详情：一次 {@code /info} 同时取作者性别与评分。
 *
 * <p>该线路是共享云手机，响应约 19KB/本、容量很小；本类只负责单次解析，
 * 批量、间隔、优先级全部交给 {@code BookMetaEnricher}，避免这里被误用成批量抓取。
 * 线路选择与冷却由 {@link ThirdPartySources} 统一负责。</p>
 *
 * <p>字段语义（2026-10-24 实测）：{@code author_info.profile_gender} 0=女 / 1=男 / 2=未知；
 * {@code score} 0–10，0 表示暂无评分。性别只在第一次取，评分 5 天刷新。</p>
 *
 * <p>作者性别只认 {@code author_info.profile_gender}（2026-10-25 实测三条证据）：
 * 顶层 {@code gender} 是全 16 本都与书籍频道（男频/女频）一致的频道字段，当作者性别用等于没判；
 * {@code author_info.gender} 对天蚕土豆、南瓜变成菜鸟等男性作者统一给 0，是脏值。
 * profile_gender 则与频道独立（男频书里也出现 0），缺它时按未知写库，不拿别的字段顶替，
 * 见 {@link #authorGenderOf}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ThirdPartyBookClient {

    /** 第三方参数名是下划线，与官方的驼峰相反。 */
    private static final String INFO_API = "/info";

    private final ObjectMapper objectMapper;
    private final ThirdPartySources sources;

    /** 一次 /info 的结果；字段为 null 表示第三方没给（未知/无评分）。 */
    public record BookMeta(Integer authorGender, Double rating) {
    }

    /**
     * 取一本的详情元数据（作者性别 + 评分）。
     *
     * @param bookId 书籍 ID
     * @return 元数据；未知字段为 null
     * @throws CrawlException 没有可用线路，或所有线路都返回坏形态
     */
    public BookMeta fetchMeta(String bookId) {
        if (bookId == null || bookId.isBlank()) {
            throw new IllegalArgumentException("bookId 不能为空");
        }
        return sources.fetch(INFO_API, "book_id", bookId, "第三方详情", bookId,
                body -> parseMeta(body, bookId));
    }

    /**
     * 解析 /info 响应。包级可见，单测直接覆盖坏形态，不去撞真实线路。
     *
     * @param body   响应体
     * @param bookId 书籍 ID，仅用于错误信息
     * @return 元数据
     * @throws ItemUnavailableException 书已下架（BOOK_REMOVE）——不冷却线路
     * @throws CrawlException           线路级异常（data 是堆栈字符串、非 JSON、未知 code、缺书籍对象）
     */
    BookMeta parseMeta(String body, String bookId) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException ioException) {
            throw new CrawlException("第三方详情返回的不是合法 JSON（bookId=" + bookId + "）", ioException);
        }
        JsonNode data = root.path("data");
        if (data.isTextual()) {
            // 故障时 data 变成 Xposed 堆栈字符串，isSuccess 仍可能为 true
            throw new CrawlException("第三方详情异常: " + abbreviate(data.asText()));
        }
        if (!"0".equals(data.path("code").asText(""))) {
            String code = data.path("code").asText("");
            // 2026-10-25 实测：不存在的书返回 200 + isSuccess:true + code=101109 message=BOOK_REMOVE。
            // 这是书的错不是线路的错，必须用 ItemUnavailableException 上抛，不能冷却整条线路。
            if ("BOOK_REMOVE".equals(data.path("message").asText(""))) {
                throw new ItemUnavailableException("第三方详情: 书籍已下架（bookId=" + bookId + ", code=" + code + "）");
            }
            throw new CrawlException("第三方详情返回 code=" + code + "（bookId=" + bookId + "）");
        }
        JsonNode book = data.path("data");
        if (!book.isObject() || book.path("book_id").asText("").isBlank()) {
            // code=0 却没有书籍对象：更像线路返回了残缺内容，按线路故障处理，别静默跳过这本书
            throw new CrawlException("第三方详情缺少书籍对象（bookId=" + bookId + "）");
        }
        return new BookMeta(authorGenderOf(book), ratingOf(book));
    }

    /**
     * 作者性别只看 {@code author_info.profile_gender}；0/1 之外（含缺失、2）一律按未知处理。
     *
     * <p>不再回退到顶层 {@code gender} 或 {@code author_info.gender}：前者是书籍频道
     * （男频 1 / 女频 0），后者实测对多位男性作者都给 0。宁可写未知，也不能把频道当作者性别。</p>
     */
    private static Integer authorGenderOf(JsonNode book) {
        int raw = genderCode(book.path("author_info").path("profile_gender"));
        return raw == 0 || raw == 1 ? raw : null;
    }

    /** 性别码可能是数字也可能是字符串（顶层 gender 实测是 "1"）；其它形态按缺失处理。 */
    private static int genderCode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return -1;
        }
        if (node.isNumber()) {
            return node.asInt(-1);
        }
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asText().trim());
            } catch (NumberFormatException malformed) {
                return -1;
            }
        }
        return -1;
    }

    /** 评分 0–10；0 表示暂无评分，按 null 处理。 */
    private static Double ratingOf(JsonNode book) {
        double score = book.path("score").asDouble(0);
        return score > 0 && score <= 10 ? score : null;
    }

    private static String abbreviate(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ");
        return flat.length() > 90 ? flat.substring(0, 90) + "…" : flat;
    }
}
