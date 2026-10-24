package org.example.fqxs.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.entity.AiEngine;
import org.example.fqxs.entity.AiPrompt;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.model.AiVerdict;
import org.example.fqxs.service.AiEngineRegistry;
import org.example.fqxs.service.AiKeyStore;
import org.example.fqxs.service.AiPromptRegistry;
import org.example.fqxs.service.AiPromptTemplates;
import org.example.fqxs.service.AiUsageRecorder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * AI 文判定的外部引擎调用：统一走 OpenAI 兼容对话接口，输出 0–100 的 AI 概率。
 *
 * <p>引擎（GLM / 千帆 / 硅基流动 / 用户自带端点）不按厂商分支，全部由注册表里的
 * baseUrl / model / key 驱动，判定只保留 OpenAI 兼容一种协议。</p>
 *
 * <p>正文只作为请求体发送，<b>本类不落库、不打日志</b>。正文的唯一落库点是
 * {@code novel_chapter}（官方前 10 章缓存，由 {@code ChapterContentService} 写入），
 * 目的是省掉重复抓取，属于刻意保留的例外。</p>
 */
@Slf4j
@Component
public class AiDetectClient {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json;charset=UTF-8");

    /** 对 429 与 5xx 的重试次数上限（不含首次）。 */
    private static final int MAX_RETRIES = 1;

    /** 重试前的退避间隔。 */
    private static final long RETRY_BACKOFF_MILLIS = 2000;

    /** 可重试的失败形态：限流与服务端瞬时故障。 */
    private static final Pattern RETRYABLE_CODE = Pattern.compile("上游返回 (?:429|5\\d\\d)");

    /** 从模型回复里兜底提取概率：模型即使加了说明文字，也要能读出数字。 */
    private static final Pattern AI_PROBABILITY_PATTERN =
            Pattern.compile("\"?ai_probability\"?\\s*[:：]\\s*(\\d+(?:\\.\\d+)?)");

    /**
     * 判别提示词不再写死在这里：改由 {@code ai_prompt} 表提供，每次判定从启用池里随机取一条
     * （见 {@link AiPromptRegistry}）。内置变体与内存兜底版在 {@link AiPromptTemplates}。
     */

    private final OkHttpClient detectHttpClient;
    private final ObjectMapper objectMapper;
    private final AiEngineRegistry engineRegistry;
    private final AiKeyStore aiKeyStore;
    private final AiPromptRegistry promptRegistry;
    private final AiUsageRecorder usageRecorder;
    private final CallQuota callQuota;

    public AiDetectClient(OkHttpClient httpClient, ObjectMapper objectMapper, AiDetectConfig aiDetectConfig,
                          AiEngineRegistry engineRegistry, AiKeyStore aiKeyStore,
                          AiPromptRegistry promptRegistry, AiUsageRecorder usageRecorder, CallQuota callQuota) {
        // 判定接口远比榜单慢：复用连接池但单独放宽读超时，避免把抓榜单的超时拖长
        this.detectHttpClient = httpClient.newBuilder()
                .readTimeout(aiDetectConfig.getTimeoutSeconds(), TimeUnit.SECONDS)
                .callTimeout(aiDetectConfig.getTimeoutSeconds() + 10, TimeUnit.SECONDS)
                // 不跟 3xx（安全测试报告 H3）：端点地址是可以在页面上改的，
                // 一个 302 就能把带 Bearer 的请求带到 169.254.169.254（云元数据）或内网。
                // 正常的 OpenAI 兼容端点不会靠重定向寻址；需要跟随 302 的是第三方书源通道，
                // 那条走的是共享 httpClient，不受这里影响
                .followRedirects(false)
                .followSslRedirects(false)
                // SSRF 闸门挂在解析层：域名保存时是公网、请求时可能变成内网
                // （DNS rebinding）只有建连那一刻的解析结果才作数。三个出口（判定 / 测试引擎 /
                // 拉取型号）共用这个 client，所以不需要各自记得检查
                .dns(new GuardedDns())
                .build();
        this.objectMapper = objectMapper;
        this.engineRegistry = engineRegistry;
        this.aiKeyStore = aiKeyStore;
        this.promptRegistry = promptRegistry;
        this.usageRecorder = usageRecorder;
        this.callQuota = callQuota;
    }

    /**
     * 按注册表记录分发判定：不再按厂商分支，统一用记录的 baseUrl / model / key。
     *
     * @param engine 引擎标识
     * @param model  本次实际调用的型号（型号池里随机取到的那个）；为空则用记录里的主型号
     * @param text   章节正文（调用方已截断）
     * @return 判定结论
     * @throws IllegalArgumentException 引擎未知、已停用、非通用通道或未配置密钥
     * @throws CrawlException           上游失败或返回无法解析
     */
    public AiVerdict detectFree(String engine, String model, String text) {
        AiEngine record = engineRegistry.require(engine);
        // 启用/停用/兼容性由 AiEngineRouter 统一判定；这里只留最后一道 Key 存在性，
        // 防止两次调用之间密钥被清空
        String apiKey = aiKeyStore.currentKey(engine);
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("引擎 " + engine + " 未配置 API Key，请在「API 密钥」面板配置");
        }
        String useModel = model == null || model.isBlank() ? record.getModel() : model;
        // 每次判定重新掷一次提示词：同一批书会摊到不同变体上，判据视角不再共用一套盲区
        AiPrompt prompt = promptRegistry.pick(null);
        return detectByOpenAiCompatible(record.getBaseUrl(), apiKey, useModel, engine, text, prompt);
    }

    /**
     * 用引擎主型号判定（单模型调用方的便捷重载）。
     *
     * @param engine 引擎标识
     * @param text   章节正文
     * @return 判定结论
     */
    public AiVerdict detectFree(String engine, String text) {
        return detectFree(engine, null, text);
    }

    /** 连通性自检结果。 */
    public record ProbeResult(boolean ok, long responseMs, String message) {
    }

    /** 自检样例：要求模型按真实判定的输出契约回一行 JSON。 */
    private static final String PROBE_TEXT = "请只输出一行 JSON：{\"ai_probability\": 50, \"reason\": \"连通性测试\"}";

    /**
     * 用一条最小样例测试引擎：既验证端点与鉴权，也验证模型会不会按契约输出可解析的 JSON。
     *
     * <p>失败不抛异常，直接把原因放进结果——面板要能一次展示"通没通、为什么不通"。</p>
     *
     * @param engine 引擎标识
     * @return 自检结果
     */
    public ProbeResult probe(String engine) {
        AiEngine record = engineRegistry.require(engine);
        if (!record.isOpenAiCompatible()) {
            return new ProbeResult(false, 0, "该引擎不是 OpenAI 兼容通道，不能用通用样例测试");
        }
        String apiKey = aiKeyStore.currentKey(engine);
        if (apiKey.isBlank()) {
            return new ProbeResult(false, 0, "未配置 API Key");
        }
        long start = System.currentTimeMillis();
        try {
            // 与真实判定同一选路：固定策略用 default-prompt，随机策略掷一次；默认变体停用也不至于测不了
            AiVerdict verdict = detectByOpenAiCompatible(record.getBaseUrl(), apiKey, record.getModel(),
                    engine, PROBE_TEXT, promptRegistry.pick(null));
            long cost = System.currentTimeMillis() - start;
            return new ProbeResult(true, cost,
                    "连接成功，输出契约可解析（样例概率 " + verdict.aiProbability() + "%）");
        } catch (RuntimeException exception) {
            long cost = System.currentTimeMillis() - start;
            return new ProbeResult(false, cost,
                    exception.getMessage() == null ? "测试失败" : exception.getMessage());
        }
    }

    /**
     * 从端点拉取型号清单（OpenAI 兼容 GET /models）。
     *
     * <p>会校验 data 数组非空：有的平台限流时返回 {code,message} 而没有 data，
     * 不校验就会把"被限流"误当成"该端点没有型号"。</p>
     *
     * @param engine 引擎标识
     * @return 型号 id 列表（去重、非空）
     */
    public List<String> discoverModels(String engine) {
        AiEngine record = engineRegistry.require(engine);
        if (!record.isOpenAiCompatible()) {
            throw new IllegalArgumentException("该引擎不是 OpenAI 兼容通道，不能拉取型号");
        }
        String apiKey = aiKeyStore.currentKey(engine);
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("未配置 API Key，无法拉取型号");
        }
        Request request = new Request.Builder()
                .url(modelsUrl(record.getBaseUrl()))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .get()
                .build();
        try (Response response = detectHttpClient.newCall(request).execute()) {
            String bodyText = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new CrawlException("拉取型号失败：上游返回 " + response.code());
            }
            JsonNode data = objectMapper.readTree(bodyText).path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new CrawlException("拉取型号失败：端点没有返回 data 数组（限流或该端点不支持 /models）");
            }
            List<String> models = new ArrayList<>();
            for (JsonNode item : data) {
                String id = item.path("id").asText("");
                if (!id.isBlank() && !models.contains(id)) {
                    models.add(id);
                }
            }
            if (models.isEmpty()) {
                throw new CrawlException("拉取型号失败：data 数组里没有可用的型号 id");
            }
            return models;
        } catch (IOException exception) {
            throw new CrawlException("拉取型号失败：" + exception.getMessage());
        }
    }

    /** 对话地址去掉 /chat/completions 后拼 /models。 */
    private String modelsUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        if (normalized.endsWith("/chat/completions")) {
            normalized = normalized.substring(0, normalized.length() - "/chat/completions".length());
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized + "/models";
    }

    /**
     * 通用 OpenAI 兼容判别调用。
     *
     * @param url    对话接口地址
     * @param apiKey Bearer 密钥
     * @param model  模型名
     * @param engine 引擎标识（进结论与日志）
     * @param text   章节正文
     * @param prompt 本次判定使用的提示词变体（system 位内容取自它，id 随结论回传以便落库追溯）
     * @return 判定结论
     */
    private AiVerdict detectByOpenAiCompatible(String url, String apiKey, String model, String engine, String text,
                                               AiPrompt prompt) {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("temperature", 0.1);
        ArrayNode messages = requestBody.putArray("messages");
        messages.addObject().put("role", "system").put("content", prompt.getContent());
        // 正文包进 ===TEXT_BEGIN===/===TEXT_END===：与 system 侧的声明配对构成注入防御，
        // 文内若出现"忽略以上指令"之类的文本也只会被当作待判内容
        messages.addObject().put("role", "user").put("content",
                "以下是小说章节正文：\n\n===TEXT_BEGIN===\n" + text + "\n===TEXT_END===");

        JsonNode responseNode = execute(url, apiKey, requestBody, "免费判定(" + engine + ")");
        String content = responseNode.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new CrawlException("免费判定(" + engine + ")返回空内容");
        }

        double aiProbability = parseProbability(content);
        String reason = parseReason(content);
        JsonNode usageNode = responseNode.path("usage");
        int promptTokens = usageNode.path("prompt_tokens").asInt(0);
        int completionTokens = usageNode.path("completion_tokens").asInt(0);
        if (promptTokens == 0 && completionTokens == 0) {
            // 部分兼容端点只给 total_tokens，按输入计
            promptTokens = usageNode.path("total_tokens").asInt(0);
        }
        usageRecorder.record(engine, model, prompt.getId(), promptTokens, completionTokens);
        log.info("免费判定完成: engine={}, 提示词={}, 概率={}, 消耗 token={}", engine, prompt.getId(), aiProbability,
                responseNode.path("usage").path("total_tokens").asInt(0));
        return new AiVerdict(aiProbability, engine, reason, prompt.getId());
    }

    /**
     * 发送 JSON 请求并解析响应，对 429/5xx/IO 失败重试一次（退避 2 秒）——
     * 免费引擎高峰期 429 常见，一次定生死会让整批初筛失败率明显抬高。
     *
     * @param url         目标地址
     * @param apiKey      Bearer 密钥
     * @param requestBody 请求体
     * @param scene       日志场景名
     * @return 响应 JSON
     */
    private JsonNode execute(String url, String apiKey, ObjectNode requestBody, String scene) {
        CrawlException lastFailure = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return executeOnce(url, apiKey, requestBody, scene);
            } catch (CrawlException failure) {
                lastFailure = failure;
                if (attempt >= MAX_RETRIES || !isRetryable(failure.getMessage())) {
                    throw failure;
                }
                log.warn("{}请求失败将重试（第 {} 次）: {}", scene, attempt + 1, failure.getMessage());
                sleepBeforeRetry();
            }
        }
        throw lastFailure;
    }

    /** 429 与 5xx 值得再试；401/403 是密钥问题，重试没有意义。 */
    private boolean isRetryable(String message) {
        if (message == null) {
            return false;
        }
        if (message.contains("鉴权失败")) {
            return false;
        }
        if (RETRYABLE_CODE.matcher(message).find()) {
            return true;
        }
        String lower = message.toLowerCase();
        return lower.contains("timed out") || lower.contains("timeout") || lower.contains("connection");
    }

    /**
     * 从 401/403 响应体区分「Key 问题」与「模型不存在/无权限」。
     * 千帆对未开通的模型也回 401 + invalid_model，笼统报「Key 无效」会误导排查方向。
     */
    private String authErrorHint(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            String code = node.path("error").path("code").asText("");
            if (!code.isBlank() && code.toLowerCase().contains("model")) {
                return "模型不存在或无权限（" + code + "），请检查型号名或先在服务商控制台开通";
            }
            if (!code.isBlank()) {
                return "API Key 无效或已过期（" + code + "）";
            }
        } catch (Exception ignored) {
            // 非 JSON 响应走通用文案
        }
        return "API Key 无效或已过期";
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_BACKOFF_MILLIS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 单次发送 JSON 请求并解析响应。
     *
     * @param url         目标地址
     * @param apiKey      Bearer 密钥
     * @param requestBody 请求体
     * @param scene       日志场景名
     * @return 响应 JSON
     */
    private JsonNode executeOnce(String url, String apiKey, ObjectNode requestBody, String scene) {
        // 日调用上限（安全测试报告 M1）：唯一的出网口在这里把关，超了就不再打上游，
        // 新增判定入口不用各自记得检查
        callQuota.requireAvailable();
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), JSON_MEDIA_TYPE))
                .build();
        long start = System.currentTimeMillis();
        try (Response response = detectHttpClient.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            String body = responseBody == null ? "" : new String(responseBody.bytes(), StandardCharsets.UTF_8);
            if (!response.isSuccessful()) {
                // 带上状态码与响应片段：失败留证要能区分 429 与 500，也要能复查上游到底写了什么
                if (response.code() == 401 || response.code() == 403) {
                    throw CrawlException.upstream(scene + "鉴权失败（" + response.code() + "）："
                            + authErrorHint(body), response.code(), body);
                }
                throw CrawlException.upstream(scene + "失败: 上游返回 " + response.code(), response.code(), body);
            }
            if (body.isBlank()) {
                throw CrawlException.upstream(scene + "返回空响应", response.code(), null);
            }
            log.debug("{}请求完成: 耗时 {} ms", scene, System.currentTimeMillis() - start);
            return objectMapper.readTree(body);
        } catch (IOException ioException) {
            throw new CrawlException(scene + "请求失败: " + ioException.getMessage(), ioException);
        }
    }

    /**
     * 从模型回复中取出概率：优先按 JSON 解析，失败再用正则兜底。
     *
     * @param content 模型回复
     * @return 0–100 的概率
     */
    private double parseProbability(String content) {
        int jsonStart = content.indexOf('{');
        int jsonEnd = content.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            try {
                JsonNode verdictNode = objectMapper.readTree(content.substring(jsonStart, jsonEnd + 1));
                if (verdictNode.hasNonNull("ai_probability")) {
                    return clamp(verdictNode.path("ai_probability").asDouble());
                }
            } catch (IOException ignored) {
                // 落到正则兜底：模型偶尔会在 JSON 前后加说明文字
            }
        }
        Matcher matcher = AI_PROBABILITY_PATTERN.matcher(content);
        if (matcher.find()) {
            return clamp(Double.parseDouble(matcher.group(1)));
        }
        throw new CrawlException("模型返回无法解析出 ai_probability");
    }

    /**
     * 取出模型给的理由；缺失时返回空串。
     *
     * @param content 模型回复
     * @return 不超过 60 字的理由
     */
    private String parseReason(String content) {
        int jsonStart = content.indexOf('{');
        int jsonEnd = content.lastIndexOf('}');
        if (jsonStart < 0 || jsonEnd <= jsonStart) {
            return "";
        }
        try {
            JsonNode verdictNode = objectMapper.readTree(content.substring(jsonStart, jsonEnd + 1));
            String reason = verdictNode.path("reason").asText("");
            return reason.length() > 60 ? reason.substring(0, 60) : reason;
        } catch (IOException ignored) {
            return "";
        }
    }

    /** 概率钳到 [0, 100]：模型偶尔给 105 之类的越界值。 */
    private double clamp(double value) {
        return Math.max(0, Math.min(100, value));
    }
}
