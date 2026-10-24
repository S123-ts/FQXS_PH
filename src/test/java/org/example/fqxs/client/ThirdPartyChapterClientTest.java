package org.example.fqxs.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.model.ChapterSample;
import org.example.fqxs.util.PuaDecoder;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第三方正文通道的契约：状态位不可信、失败进冷却、还原口径与官方共用同一份。
 * 全部用 OkHttp 拦截器打桩，不连接真实服务。
 */
class ThirdPartyChapterClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private AiDetectConfig config() {
        AiDetectConfig config = new AiDetectConfig();
        config.setThirdPartyCooldownSeconds(60);
        return config;
    }

    private ThirdPartyChapterClient client(AiDetectConfig config, Interceptor interceptor) {
        // 线路寻址已抽到 ThirdPartySources：打桩的拦截器装在它持有的 OkHttpClient 上
        okhttp3.OkHttpClient http =
                new okhttp3.OkHttpClient.Builder().addInterceptor(interceptor).build();
        return new ThirdPartyChapterClient(mapper, new PuaDecoder(mapper), config,
                new ThirdPartySources(http, config));
    }

    private Response mocked(int code, String body) {
        return new Response.Builder()
                .request(new Request.Builder().url("https://fq-book.netsite.cc/content?item_id=c1").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("mock")
                .body(ResponseBody.create(body, MediaType.get("application/json")))
                .build();
    }

    /** 上游会把 Xposed 堆栈装进 data 的同时 isSuccess 仍为 true，因此只能按正文形态判失败。 */
    @Test
    void stackPayloadIsFailureEvenWhenSuccessFlagIsTrue() {
        String body = "{\"data\":\"de.robv.android.xposed.XposedHelpers$InvocationTargetError: "
                + "java.lang.NullPointerException: onNext called with null\",\"isSuccess\":true}";
        ThirdPartyChapterClient client = client(config(), chain -> mocked(200, body));

        assertThrows(CrawlException.class, () -> client.fetchChapter("c1"));
        assertFalse(client.isUsable(), "结构性失败要进冷却，避免后续每本书重复尝试");
    }

    /** 第三方返回的同样是 PUA 混淆串：复用官方那张表就能还原，不需要第二份映射。 */
    @Test
    void contentIsDecodedWithTheSamePuaTable() {
        // 码位 U+E3E8 在映射表首位，明文是 "D"
        String body = "{\"data\":{\"code\":\"0\",\"data\":{\"title\":\"第一章\",\"content\":\"\\uE3E8\\uE3E8\"}}}";
        ThirdPartyChapterClient client = client(config(), chain -> mocked(200, body));

        ChapterSample sample = client.fetchChapter("c1");

        assertEquals("DD", sample.text());
        assertEquals("第一章", sample.title());
        assertTrue(client.isUsable(), "成功不该触发冷却");
    }

    @Test
    void blankContentCoolsDownTheChannel() {
        String body = "{\"data\":{\"code\":\"0\",\"data\":{\"title\":\"第一章\",\"content\":\"\"}}}";
        ThirdPartyChapterClient client = client(config(), chain -> mocked(200, body));

        assertThrows(CrawlException.class, () -> client.fetchChapter("c1"));
        assertFalse(client.isUsable());
    }

    /** 共享线路挂了的时候重试只会加压，所以这里钉住"只打一次"。 */
    @Test
    void serverErrorsAreNotRetriedButCoolDown() {
        AtomicInteger calls = new AtomicInteger();
        ThirdPartyChapterClient client = client(config(), chain -> {
            calls.incrementAndGet();
            return mocked(503, "busy");
        });

        assertThrows(CrawlException.class, () -> client.fetchChapter("c1"));
        assertEquals(1, calls.get());
        assertFalse(client.isUsable());
    }

    @Test
    void disabledByConfigNeverAsksTheChannel() {
        AiDetectConfig config = config();
        config.setThirdPartyEnabled(false);
        AtomicInteger calls = new AtomicInteger();
        ThirdPartyChapterClient client = client(config, chain -> {
            calls.incrementAndGet();
            return mocked(200, "{}");
        });

        assertFalse(client.isUsable());
        assertEquals(0, calls.get());
    }
}
