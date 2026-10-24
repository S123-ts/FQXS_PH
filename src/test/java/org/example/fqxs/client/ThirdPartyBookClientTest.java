package org.example.fqxs.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.exception.CrawlException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 第三方 /info 的解析契约：0/1 才是性别，2 是未知；score 0 表示暂无评分。
 * 坏形态（code 非 0、data 是堆栈字符串、缺书籍对象）必须抛错，不能当“无数据”写库。
 */
class ThirdPartyBookClientTest {

    private final ThirdPartyBookClient client = new ThirdPartyBookClient(new ObjectMapper(),
            new ThirdPartySources(new OkHttpClient(), new AiDetectConfig()));

    @Test
    void parsesGenderAndRating() {
        String body = """
                {"data":{"code":"0","data":{"book_id":"1","score":"8.6",
                  "author_info":{"gender":"1"}}},"isSuccess":true}
                """;

        ThirdPartyBookClient.BookMeta meta = client.parseMeta(body, "1");

        assertEquals(1, meta.authorGender());
        assertEquals(8.6, meta.rating(), 0.001);
    }

    @Test
    void genderTwoAndZeroScoreBecomeUnknown() {
        String body = """
                {"data":{"code":"0","data":{"book_id":"1","score":"0",
                  "author_info":{"gender":"2"}}}}
                """;

        ThirdPartyBookClient.BookMeta meta = client.parseMeta(body, "1");

        assertNull(meta.authorGender());
        assertNull(meta.rating());
    }

    @Test
    void nonZeroCodeIsRejected() {
        String body = "{\"data\":{\"code\":\"500\",\"data\":{}}}";

        assertThrows(CrawlException.class, () -> client.parseMeta(body, "1"));
    }

    @Test
    void stackTraceStringIsRejected() {
        String body = "{\"data\":\"java.lang.NullPointerException: onNext called with null\"}";

        assertThrows(CrawlException.class, () -> client.parseMeta(body, "1"));
    }

    @Test
    void missingBookObjectIsRejected() {
        String body = "{\"data\":{\"code\":\"0\",\"data\":{}}}";

        assertThrows(CrawlException.class, () -> client.parseMeta(body, "1"));
    }
}
