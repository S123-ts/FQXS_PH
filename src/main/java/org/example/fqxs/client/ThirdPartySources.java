package org.example.fqxs.client;

import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.exception.ItemUnavailableException;
import org.example.fqxs.exception.LineCoolingException;
import org.example.fqxs.logging.AppLogEntry;
import org.example.fqxs.logging.BookLogContext;
import org.example.fqxs.logging.LogBuffer;
import org.example.fqxs.logging.ThirdPartyLogEntry;
import org.example.fqxs.util.DateUtil;
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
 *
 * <p>唯一例外是 {@link ItemUnavailableException}（书已下架 / 正文不存在）：这说明线路是好的，
 * 只是这一本书没有数据。它直接上抛、不冷却任何线路——否则一本坏书就能让整条共享线路休息 5 分钟。</p>
 *
 * <p><b>日志</b>：每次线路尝试（不是每次 fetch）都往 {@code third_party_log} 落一行，
 * 换线重试时一次抽取会产出多行。这样「哪条线路今天挂了几次、失败形态是什么」能直接数出来。
 * 埋点不改变任何控制流：状态码与字节数先攒在 {@link Attempt} 里，成功失败都在 finally 前记一次。</p>
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
        return fetch(path, paramName, paramValue, scene, null, parser);
    }

    /**
     * 同上，额外带上书 id 以便日志归到那本书名下。
     *
     * @param bookId 书 id；调用方知道就传，不知道（如只有 item_id 的正文请求）传 null，
     *               这里回退到 {@link BookLogContext#currentBookId()}（调用链用 MDC 圈定过的话）
     */
    public <T> T fetch(String path, String paramName, String paramValue, String scene, String bookId,
                       Function<String, T> parser) {
        List<String> candidates = usable();
        if (candidates.isEmpty()) {
            throw new LineCoolingException(scene + "不可用：第三方线路全部在冷却中或已关闭");
        }
        String effectiveBookId = bookId == null || bookId.isBlank()
                ? BookLogContext.currentBookId()
                : bookId;
        CrawlException lastFailure = null;
        for (String base : candidates) {
            long start = System.currentTimeMillis();
            Attempt attempt = new Attempt();
            try {
                String body = execute(base, path, paramName, paramValue, scene, attempt);
                T result = parser.apply(body);
                record(base, path, scene, paramName, paramValue, effectiveBookId,
                        attempt, ThirdPartyLogEntry.OK, null, start);
                return result;
            } catch (ItemUnavailableException itemFailure) {
                // 书的错不是线路的错：不冷却、不换线（换一条也是同一本书）
                record(base, path, scene, paramName, paramValue, effectiveBookId,
                        attempt, ThirdPartyLogEntry.ITEM_UNAVAILABLE, itemFailure.getMessage(), start);
                throw itemFailure;
            } catch (CrawlException failure) {
                // execute 已经成功但解析失败时，attempt.outcome 还是 OK，这里改判 PARSE_ERROR
                String outcome = ThirdPartyLogEntry.OK.equals(attempt.outcome)
                        ? ThirdPartyLogEntry.PARSE_ERROR
                        : attempt.outcome;
                record(base, path, scene, paramName, paramValue, effectiveBookId,
                        attempt, outcome, failure.getMessage(), start);
                lastFailure = failure;
                markUnavailable(base, failure.getMessage());
            }
        }
        throw lastFailure;
    }

    /**
     * 记一次线路尝试并落 {@code third_party_log}。
     *
     * @param attempt 请求过程中攒下的状态码 / 字节数
     * @param outcome 最终判定（OK / HTTP_ERROR / EMPTY / IO_ERROR / PARSE_ERROR / ITEM_UNAVAILABLE）
     * @param error   失败原因，成功传 null
     */
    private void record(String base, String path, String scene, String paramName, String paramValue,
                        String bookId, Attempt attempt, String outcome, String error, long start) {
        try {
            LogBuffer.offerThirdParty(new ThirdPartyLogEntry(
                    DateUtil.now(),
                    AppLogEntry.truncate(base, ThirdPartyLogEntry.LINE_MAX),
                    AppLogEntry.truncate(path, ThirdPartyLogEntry.PATH_MAX),
                    AppLogEntry.truncate(scene, ThirdPartyLogEntry.SCENE_MAX),
                    AppLogEntry.truncate(paramName, ThirdPartyLogEntry.PARAM_NAME_MAX),
                    AppLogEntry.truncate(paramValue, ThirdPartyLogEntry.PARAM_VALUE_MAX),
                    AppLogEntry.truncate(bookId, ThirdPartyLogEntry.BOOK_ID_MAX),
                    attempt.status,
                    attempt.bytes,
                    (int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - start),
                    outcome,
                    AppLogEntry.truncate(error, ThirdPartyLogEntry.ERROR_MAX)));
        } catch (RuntimeException ignored) {
            // 埋点失败不能影响抽取
        }
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
     * @param attempt    出参：把状态码、字节数与失败形态带回去给日志
     * @return 响应体文本
     */
    private String execute(String base, String path, String paramName, String paramValue, String scene,
                           Attempt attempt) {
        String url;
        try {
            url = HttpUrl.get(base + path).newBuilder()
                    .addQueryParameter(paramName, paramValue)
                    .build()
                    .toString();
        } catch (IllegalArgumentException badUrl) {
            attempt.outcome = ThirdPartyLogEntry.IO_ERROR;
            throw new CrawlException(scene + "线路地址非法: " + base, badUrl);
        }
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", ChapterClient.USER_AGENT)
                .header("Accept", "application/json,*/*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            attempt.status = response.code();
            if (!response.isSuccessful()) {
                attempt.outcome = ThirdPartyLogEntry.HTTP_ERROR;
                throw new CrawlException(scene + "抓取失败: 上游返回 " + response.code());
            }
            ResponseBody responseBody = response.body();
            byte[] raw = responseBody == null ? new byte[0] : responseBody.bytes();
            attempt.bytes = raw.length;
            String body = new String(raw, StandardCharsets.UTF_8);
            if (body.isBlank()) {
                attempt.outcome = ThirdPartyLogEntry.EMPTY;
                throw new CrawlException(scene + "返回空响应");
            }
            return body;
        } catch (IOException ioException) {
            attempt.outcome = ThirdPartyLogEntry.IO_ERROR;
            throw new CrawlException(scene + "抓取失败: " + ioException.getMessage(), ioException);
        }
    }

    private static String abbreviate(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ");
        return flat.length() > 90 ? flat.substring(0, 90) + "…" : flat;
    }

    /** 一次线路尝试的观测值：由 {@link #execute} 填，供日志记录。 */
    private static final class Attempt {
        /** HTTP 状态码；连接失败时为 null。 */
        Integer status;

        /** 响应体字节数；没读到响应时为 null。 */
        Integer bytes;

        /** 失败形态；成功保持 {@link ThirdPartyLogEntry#OK}。 */
        String outcome = ThirdPartyLogEntry.OK;
    }
}
