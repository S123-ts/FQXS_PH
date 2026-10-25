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
                  "author_info":{"profile_gender":"1"}}},"isSuccess":true}
                """;

        ThirdPartyBookClient.BookMeta meta = client.parseMeta(body, "1");

        assertEquals(1, meta.authorGender());
        assertEquals(8.6, meta.rating(), 0.001);
    }

    @Test
    void genderTwoAndZeroScoreBecomeUnknown() {
        String body = """
                {"data":{"code":"0","data":{"book_id":"1","score":"0",
                  "author_info":{"profile_gender":"2"}}}}
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

    /** 2026-10-25 实测的下架形态：200 + isSuccess:true + code=101109 message=BOOK_REMOVE。 */
    @Test
    void removedBookIsItemLevelFailure() {
        String body = """
                {"data":{"code":"101109","data":{},"message":"BOOK_REMOVE"},"isSuccess":true}
                """;

        org.example.fqxs.exception.ItemUnavailableException failure =
                assertThrows(org.example.fqxs.exception.ItemUnavailableException.class,
                        () -> client.parseMeta(body, "1"));

        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("下架"));
    }

    /** 未知 code 仍按线路故障处理，不能统统当成「书没了」。 */
    @Test
    void unknownCodeStaysLineLevel() {
        String body = "{\"data\":{\"code\":\"500\",\"data\":{},\"message\":\"BOOM\"}}";

        CrawlException failure = assertThrows(CrawlException.class, () -> client.parseMeta(body, "1"));

        org.junit.jupiter.api.Assertions.assertFalse(
                failure instanceof org.example.fqxs.exception.ItemUnavailableException);
    }

    /** 天蚕土豆那一本的实测形态：author_info.gender 脏成 0，profile_gender 才是真值 1（男）。 */
    @Test
    void profileGenderWinsWhenAuthorInfoGenderIsStale() {
        String body = """
                {"data":{"code":"0","data":{"book_id":"1","score":9.4,
                  "author_info":{"gender":0,"profile_gender":1}}},"isSuccess":true}
                """;

        ThirdPartyBookClient.BookMeta meta = client.parseMeta(body, "1");

        assertEquals(1, meta.authorGender());
    }

    /** 顶层 gender 是书籍频道（男频 1 / 女频 0），不能当作者性别；profile_gender 缺失就写未知。 */
    @Test
    void topLevelGenderIsChannelAndNeverUsedAsAuthorGender() {
        String body = """
                {"data":{"code":"0","data":{"book_id":"1","score":9.2,"gender":"1",
                  "author_info":{}}}}
                """;

        ThirdPartyBookClient.BookMeta meta = client.parseMeta(body, "1");

        assertNull(meta.authorGender());
    }

    /** 脏字段 author_info.gender 同样不再兜底：只有它给出 0/1 时也按未知。 */
    @Test
    void dirtyAuthorInfoGenderIsNotAFallback() {
        String body = """
                {"data":{"code":"0","data":{"book_id":"1","score":9.0,
                  "author_info":{"profile_gender":2,"gender":1}}}}
                """;

        ThirdPartyBookClient.BookMeta meta = client.parseMeta(body, "1");

        assertNull(meta.authorGender());
    }

    /** profile_gender 给不出 0/1 时按未知处理，不能靠猜。 */
    @Test
    void genderUnknownWhenNoFieldCarriesZeroOrOne() {
        String body = """
                {"data":{"code":"0","data":{"book_id":"1","score":9.0,"gender":"x",
                  "author_info":{"profile_gender":2,"gender":9}}}}
                """;

        ThirdPartyBookClient.BookMeta meta = client.parseMeta(body, "1");

        assertNull(meta.authorGender());
    }
}
