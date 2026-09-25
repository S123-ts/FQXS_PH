package org.example.fqxs.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.example.fqxs.config.FanqieConfig;
import org.example.fqxs.exception.CrawlException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FanqieApiClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private FanqieApiClient client(int maxRetries, Interceptor interceptor) {
        FanqieConfig config = new FanqieConfig();
        config.setMaxRetries(maxRetries);
        return new FanqieApiClient(
                new okhttp3.OkHttpClient.Builder().addInterceptor(interceptor).build(), mapper, config);
    }

    private Response mocked(int code, String body) {
        return new Response.Builder()
                .request(new Request.Builder().url("https://fanqienovel.com/api/rank/category/list").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("mock")
                .body(ResponseBody.create(body, MediaType.get("application/json")))
                .build();
    }

    /** crawler.fanqie.max-retries 只有在上游 5xx 时真的被用到才算数。 */
    @Test
    void serverErrorsAreRetriedThenReported() {
        AtomicInteger calls = new AtomicInteger();
        FanqieApiClient api = client(2, chain -> {
            calls.incrementAndGet();
            return mocked(503, "busy");
        });

        assertThrows(CrawlException.class, () -> api.fetchRankList("https://fanqienovel.com/x"));
        assertEquals(2, calls.get());
    }

    /** 4xx 重试不会改变结果，还会更快撞上风控。 */
    @Test
    void clientErrorsAreNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        FanqieApiClient api = client(3, chain -> {
            calls.incrementAndGet();
            return mocked(404, "not found");
        });

        CrawlException error = assertThrows(CrawlException.class, () -> api.fetchRankList("https://fanqienovel.com/x"));
        assertTrue(error.getMessage().contains("404"), error.getMessage());
        assertEquals(1, calls.get());
    }

    @Test
    void ioFailuresAreRetriedAndReported() {
        AtomicInteger calls = new AtomicInteger();
        FanqieApiClient api = client(2, chain -> {
            calls.incrementAndGet();
            throw new IOException("连接被重置");
        });

        CrawlException error = assertThrows(CrawlException.class, () -> api.fetchRankList("https://fanqienovel.com/x"));
        assertTrue(error.getMessage().contains("已尝试 2 次"), error.getMessage());
        assertEquals(2, calls.get());
    }

    @Test
    void successfulPayloadIsParsedAsJson() throws Exception {
        FanqieApiClient api = client(3, chain -> mocked(200, "{\"code\":0,\"data\":{}}"));

        JsonNode root = api.fetchRankList("https://fanqienovel.com/x");

        assertEquals(0, root.path("code").asInt());
    }
}
