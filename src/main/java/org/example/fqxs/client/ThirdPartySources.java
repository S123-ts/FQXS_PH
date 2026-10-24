package org.example.fqxs.client;

import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.exception.CrawlException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 第三方线路的寻址、逐源冷却与故障切换。
 *
 * <p>配置里是一条有序清单（{@code ai.detect.third-party-base-urls}），详情 {@code /info}、
 * 正文 {@code /content} 与目录 {@code /catalog} 三条链路共用。这些线路都是别人的共享机器，
 * 挂法有三种：连不上 / 非 2xx、HTTP 200 但空响应，以及 200 且 {@code isSuccess:true}
 * 却把故障堆栈装进 {@code data}。所以判成功的唯一依据是<b>解析出可用内容</b>，不看状态位。</p>
 *
 * <p>任何失败只冷却出错的那一条，随后自动换下一条；全部冷却时 {@link #hasUsable()} 返回 false，
 * 上层据此明确报错而不是静默降级。</p>
 */
@Slf4j
@Component
public class ThirdPartySources {

    private final OkHttpClient httpClient;
    private final AiDetectConfig aiDetectConfig;

    /** 线路地址 → 冷却截止时间戳：未到点前该线路不再被尝试。 */
    private final Map<String, Long> unavailableUntil = new ConcurrentHashMap<>();

    public ThirdPartySources(OkHttpClient httpClient, AiDetectConfig aiDetectConfig) {
        this.httpClient = httpClient;
        this.aiDetectConfig = aiDetectConfig;
    }

    /** 配置里的全部线路：去空白、按配置顺序去重。 */
    public List<String> all() {
        Set<String> distinct = new LinkedHashSet<>();
        for (String raw : aiDetectConfig.getThirdPartyBaseUrls()) {
            if (raw != null && !raw.isBlank()) {
                distinct.add(raw.trim());
            }
        }
        return List.copyOf(distinct);
    }

    /** 当前还值得尝试的线路：总开关开着且不在冷却期。 */
    public List<String> usable() {
        if (!aiDetectConfig.isThirdPartyEnabled()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<String> ready = new ArrayList<>();
        for (String base : all()) {
            if (unavailableUntil.getOrDefault(base, 0L) <= now) {
                ready.add(base);
            }
        }
        return ready;
    }

    /** 是否还有线路可试；取样器据此决定要不要明确报「会员正文通道不可用」。 */
    public boolean hasUsable() {
        return !usable().isEmpty();
    }

    /**
     * 按清单顺序请求一条线路，第一条解析成功的结果即为最终结果。
     *
     * @param path       接口路径，如 {@code /content}
     * @param paramName  上游参数名（第三方是下划线，与官方驼峰相反）
     * @param paramValue 参数值
     * @param scene      日志与异常信息里的场景名
     * @param parser     响应体解析器；解析失败要抛 {@link CrawlException} 才会触发换线
     * @param <T>        解析结果类型
     * @return 解析结果
     * @throws CrawlException 没有可用线路，或所有线路都失败（取最后一次失败）
     */
    public <T> T fetch(String path, String paramName, String paramValue, String scene,
                       Function<String, T> parser) {
        List<String> candidates = usable();
        if (candidates.isEmpty()) {
            throw new CrawlException(scene + "不可用：第三方线路全部在冷却中或已关闭");
        }
        CrawlException lastFailure = null;
        for (String base : candidates) {
            try {
                return parser.apply(execute(base, path, paramName, paramValue, scene));
            } catch (CrawlException failure) {
                lastFailure = failure;
                markUnavailable(base, failure.getMessage());
            }
        }
        throw lastFailure;
    }

    /**
     * 记一次线路故障并冷却它；冷却期内重复失败不再刷屏。
     *
     * @param base   线路地址
     * @param reason 失败原因，只进日志
     */
    private void markUnavailable(String base, String reason) {
        int cooldown = Math.max(0, aiDetectConfig.getThirdPartyCooldownSeconds());
        long now = System.currentTimeMillis();
        Long previous = unavailableUntil.put(base, now + cooldown * 1000L);
        if (previous == null || previous <= now) {
            log.warn("第三方线路进入冷却 {} 秒: {}，原因: {}", cooldown, base, abbreviate(reason));
        }
    }

    /**
     * 向指定线路发一次 GET。共享线路不做重试：它挂的时候重试只会加压，失败交给冷却处理。
     *
     * @param base       线路地址
     * @param path       接口路径
     * @param paramName  参数名
     * @param paramValue 参数值
     * @param scene      场景名
     * @return 响应体文本
     */
    private String execute(String base, String path, String paramName, String paramValue, String scene) {
        String url;
        try {
            url = HttpUrl.get(base + path).newBuilder()
                    .addQueryParameter(paramName, paramValue)
                    .build()
                    .toString();
        } catch (IllegalArgumentException badUrl) {
            throw new CrawlException(scene + "线路地址非法: " + base, badUrl);
        }
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", ChapterClient.USER_AGENT)
                .header("Accept", "application/json,*/*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new CrawlException(scene + "抓取失败: 上游返回 " + response.code());
            }
            ResponseBody responseBody = response.body();
            String body = responseBody == null ? "" : new String(responseBody.bytes(), StandardCharsets.UTF_8);
            if (body.isBlank()) {
                throw new CrawlException(scene + "返回空响应");
            }
            return body;
        } catch (IOException ioException) {
            throw new CrawlException(scene + "抓取失败: " + ioException.getMessage(), ioException);
        }
    }

    private static String abbreviate(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ");
        return flat.length() > 90 ? flat.substring(0, 90) + "…" : flat;
    }
}
