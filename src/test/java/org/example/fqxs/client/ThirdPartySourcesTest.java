package org.example.fqxs.client;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.exception.CrawlException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多源线路的契约：坏线只冷却它自己、随后自动换下一条；全部冷却才判定「通道不可用」。
 * 三种坏法（非 2xx、空响应、200 且 isSuccess 为 true 但 data 是堆栈）都要能触发换线。
 * 全部用 OkHttp 拦截器打桩，不连接真实服务。
 */
class ThirdPartySourcesTest {

    private static final String FIRST = "https://line-a.test";
    private static final String SECOND = "https://line-b.test";

    private static final String STACK_BODY =
            "{\"data\":\"de.robv.android.xposed.XposedHelpers$InvocationTargetError: "
                    + "java.lang.NullPointerException: onNext called with null\",\"isSuccess\":true}";
    private static final String GOOD_BODY = "{\"data\":{\"code\":\"0\",\"data\":{\"content\":\"正文\"}}}";

    private AiDetectConfig config(String... urls) {
        AiDetectConfig config = new AiDetectConfig();
        config.setThirdPartyBaseUrls(new ArrayList<>(Arrays.asList(urls)));
        config.setThirdPartyCooldownSeconds(60);
        return config;
    }

    private ThirdPartySources sources(AiDetectConfig config, Interceptor interceptor) {
        return new ThirdPartySources(new OkHttpClient.Builder().addInterceptor(interceptor).build(), config);
    }

    /** 线路好坏的判据只能是「解析得出内容」，与正文/详情/目录三个客户端同口径。 */
    private static String parse(String body) {
        if (body.contains("NullPointerException") || !body.contains("正文")) {
            throw new CrawlException("线路返回的是异常形态: " + body);
        }
        return body;
    }

    private static Response json(Interceptor.Chain chain, int code, String body) throws IOException {
        return new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("mock")
                .body(ResponseBody.create(body, MediaType.get("application/json")))
                .build();
    }

    /** 第一条线坏了不该让整次请求失败：冷却它，取第二条的结果。 */
    @Test
    void brokenFirstLineFallsOverToTheNextOne() {
        AtomicInteger calls = new AtomicInteger();
        ThirdPartySources sources = sources(config(FIRST, SECOND), chain -> {
            calls.incrementAndGet();
            boolean firstLine = chain.request().url().host().equals("line-a.test");
            return json(chain, 200, firstLine ? STACK_BODY : GOOD_BODY);
        });

        assertEquals(GOOD_BODY, sources.fetch("/content", "item_id", "c1", "第三方正文",
                ThirdPartySourcesTest::parse));
        assertEquals(2, calls.get(), "坏线只撞一次，不重试");
        assertEquals(List.of(SECOND), sources.usable(), "坏线进冷却，好线保持可用");
    }

    /** 全部线路都坏时才整体不可用，并把最后一次失败原因交回上层。 */
    @Test
    void everyLineFailingCoolsDownTheWholeChannel() {
        ThirdPartySources sources = sources(config(FIRST, SECOND), chain -> json(chain, 200, STACK_BODY));

        CrawlException failure = assertThrows(CrawlException.class,
                () -> sources.fetch("/content", "item_id", "c1", "第三方正文", ThirdPartySourcesTest::parse));
        assertTrue(failure.getMessage().contains("NullPointerException"), "异常原因要能追到具体线路");
        assertFalse(sources.hasUsable(), "两条都坏，整个通道该进冷却");
    }

    /** 非 2xx 与空响应同样是线路故障：不能因为 HTTP 层"有返回"就当成功。 */
    @Test
    void httpErrorAndEmptyBodyAreLineFailuresToo() {
        ThirdPartySources sources = sources(config(FIRST, SECOND), chain ->
                chain.request().url().host().equals("line-a.test")
                        ? json(chain, 503, "busy")
                        : json(chain, 200, ""));

        assertThrows(CrawlException.class,
                () -> sources.fetch("/content", "item_id", "c1", "第三方正文", ThirdPartySourcesTest::parse));
        assertFalse(sources.hasUsable());
    }

    /** 书级失败（已下架）不冷却线路：换一条线也是同一本书，但线路本身还是好的。 */
    @Test
    void itemLevelFailureDoesNotCoolTheLine() {
        AtomicInteger calls = new AtomicInteger();
        ThirdPartySources sources = sources(config(FIRST), chain -> {
            calls.incrementAndGet();
            return json(chain, 200, GOOD_BODY);
        });

        assertThrows(org.example.fqxs.exception.ItemUnavailableException.class,
                () -> sources.fetch("/info", "book_id", "1", "第三方详情", body -> {
                    throw new org.example.fqxs.exception.ItemUnavailableException("书籍已下架");
                }));
        assertTrue(sources.hasUsable(), "书的错不能连累线路冷却");

        assertEquals(GOOD_BODY, sources.fetch("/info", "book_id", "2", "第三方详情",
                ThirdPartySourcesTest::parse));
        assertEquals(2, calls.get(), "线路没有被冷却，下一次请求应该照常发出");
    }

    /** 全部线路都在冷却时抛专用异常，调用方能区分「线路在休息」与「线路坏了」。 */
    @Test
    void noUsableLineIsReportedAsCoolingNotFailure() {
        AiDetectConfig config = config(FIRST);
        config.setThirdPartyCooldownSeconds(60);
        AtomicInteger calls = new AtomicInteger();
        ThirdPartySources sources = sources(config, chain -> {
            calls.incrementAndGet();
            return json(chain, 200, STACK_BODY);
        });

        assertThrows(CrawlException.class, () -> sources.fetch("/content", "item_id", "c1", "第三方正文",
                ThirdPartySourcesTest::parse));
        assertThrows(org.example.fqxs.exception.LineCoolingException.class,
                () -> sources.fetch("/content", "item_id", "c2", "第三方正文",
                        ThirdPartySourcesTest::parse));
        assertEquals(1, calls.get(), "冷却期内不该再发请求");
    }

    /** 关掉开关一条都不问：降级形态不产生任何上游请求。 */
    @Test
    void disabledConfigNeverAsksAnyLine() {
        AiDetectConfig config = config(FIRST, SECOND);
        config.setThirdPartyEnabled(false);
        AtomicInteger calls = new AtomicInteger();
        ThirdPartySources sources = sources(config, chain -> {
            calls.incrementAndGet();
            return json(chain, 200, GOOD_BODY);
        });

        assertFalse(sources.hasUsable());
        assertThrows(CrawlException.class,
                () -> sources.fetch("/content", "item_id", "c1", "第三方正文", ThirdPartySourcesTest::parse));
        assertEquals(0, calls.get(), "未启用时不该发出请求");
    }

    /** 冷却期内不再重复撞坏线，否则每本书都要为它白等一次。 */
    @Test
    void cooledDownLineIsSkippedByLaterRequests() {
        AtomicInteger firstLineCalls = new AtomicInteger();
        ThirdPartySources sources = sources(config(FIRST, SECOND), chain -> {
            if (chain.request().url().host().equals("line-a.test")) {
                firstLineCalls.incrementAndGet();
                return json(chain, 200, STACK_BODY);
            }
            return json(chain, 200, GOOD_BODY);
        });

        sources.fetch("/content", "item_id", "c1", "第三方正文", ThirdPartySourcesTest::parse);
        sources.fetch("/content", "item_id", "c2", "第三方正文", ThirdPartySourcesTest::parse);

        assertEquals(1, firstLineCalls.get(), "坏线冷却后，第二章节请求不该再撞它");
    }

    /** 清单里的空白与重复项都不该产生第二次请求。 */
    @Test
    void blankAndDuplicateEntriesAreIgnored() {
        ThirdPartySources sources = new ThirdPartySources(new OkHttpClient(),
                config(FIRST, "  ", FIRST, SECOND));

        assertEquals(List.of(FIRST, SECOND), sources.all());
    }

    /** 地址写非法（拼不出 URL）也要按线路故障处理，不能把异常抛穿到业务层。 */
    @Test
    void malformedBaseUrlIsReportedAsLineFailure() {
        ThirdPartySources sources = sources(config("not a url"), chain -> json(chain, 200, GOOD_BODY));

        CrawlException failure = assertThrows(CrawlException.class,
                () -> sources.fetch("/content", "item_id", "c1", "第三方正文", ThirdPartySourcesTest::parse));
        assertTrue(failure.getMessage().contains("非法"), "要能一眼看出是地址写错了");
    }
}
