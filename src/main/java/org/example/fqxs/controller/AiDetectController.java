package org.example.fqxs.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.model.AiKeyUpdateRequest;
import org.example.fqxs.model.SampleSpec;
import org.example.fqxs.service.AiDetectService;
import org.example.fqxs.service.AiEngineRegistry;
import org.example.fqxs.service.AiKeyStore;
import org.example.fqxs.service.AiQuotaService;
import org.example.fqxs.service.AiUsageRecorder;
import org.example.fqxs.service.ReviewActivity;
import org.example.fqxs.web.ApiResponse;
import org.example.fqxs.web.dto.EngineStatus;
import org.example.fqxs.web.dto.UsageSummary;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 判定接口：状态、密钥、额度与单本/批量/逐章判定。
 *
 * <p>引擎管理见 {@link AiEngineController}，提示词查询见 {@link AiPromptController}。
 * 会触发外部调用与写库的动作都是 POST（与 /api/rank/** 同一套写令牌保护），
 * 状态与结论查询是 GET。</p>
 */
@Slf4j
@Tag(name = "AI 判定")
@RestController
@RequestMapping(value = "/api/ai", produces = MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
@RequiredArgsConstructor
public class AiDetectController {

    /** 逐引擎结论单次查询的书数上限。 */
    private static final int VERDICT_QUERY_LIMIT = 500;

    private final AiDetectService aiDetectService;

    private final AiKeyStore aiKeyStore;

    private final AiQuotaService aiQuotaService;

    private final AiEngineRegistry aiEngineRegistry;

    private final AiUsageRecorder aiUsageRecorder;

    private final ReviewActivity reviewActivity;

    /** 引擎就绪情况与判定分布，页面引导时读一次。 */
    @Operation(summary = "引擎就绪与判定分布")
    @GetMapping("/status")
    public ApiResponse<AiDetectService.StatusView> status() {
        return ApiResponse.data("success", aiDetectService.status());
    }

    /**
     * 各引擎密钥的配置状态（只回掩码，永不回明文）。
     *
     * @return {engine: {configured, masked, source, model, models, modelPool, ...}}
     */
    @Operation(summary = "各引擎密钥状态（仅掩码）")
    @GetMapping("/keys")
    public ApiResponse<Map<String, EngineStatus>> keys() {
        return ApiResponse.data("success", aiEngineRegistry.describeAll());
    }

    /**
     * 保存或清除一个引擎的密钥：立即生效，并写入本地 data/ai-keys.properties。
     *
     * @param request 引擎标识与密钥（空串表示清除）
     * @return 保存后的全部引擎状态
     */
    @Operation(summary = "保存或清除引擎密钥")
    @PostMapping("/keys")
    public ApiResponse<Map<String, EngineStatus>> saveKey(@Valid @RequestBody AiKeyUpdateRequest request) {
        boolean clearing = request.key() == null || request.key().isBlank();
        log.info("保存 AI 密钥: engine={}, 动作={}", request.engine(), clearing ? "清除" : "写入");
        aiKeyStore.save(request.engine(), request.key());
        String message = clearing
                ? "已清除 " + request.engine() + " 的密钥"
                : "已保存 " + request.engine() + " 的密钥，立即生效";
        return ApiResponse.data(message, aiEngineRegistry.describeAll());
    }

    /**
     * 查询各引擎的额度情况（余额 / 免费规则 / 有效期说明）。
     *
     * <p>用 POST 而不是 GET：它会向硅基流动发起一次外部查询，按本项目约定
     * 「会触发外部调用的动作一律 POST + 写令牌」，避免被跨站 GET 反复触发。</p>
     *
     * @return 引擎标识 → 额度信息
     */
    @Operation(summary = "查询各引擎额度")
    @PostMapping("/quota")
    public ApiResponse<Map<String, Map<String, Object>>> quota() {
        return ApiResponse.data("success", aiQuotaService.query());
    }

    /**
     * 最近 N 天的调用用量（次数与 token，不估成本）。
     *
     * @param days 天数，默认 7，钳到 1–90
     * @return {days, byEngine, totals}
     */
    @Operation(summary = "近 N 天调用用量")
    @GetMapping("/usage")
    public ApiResponse<UsageSummary> usage(@RequestParam(defaultValue = "7") int days) {
        int safeDays = Math.max(1, Math.min(days, 90));
        return ApiResponse.data("success", aiUsageRecorder.summary(safeDays));
    }

    /**
     * 免费引擎判定单本。
     *
     * <p>默认幂等：该引擎已有初筛结论时直接返回（cached=true），不消耗模型额度；
     * force=true 强制重新判定。</p>
     *
     * @param bookId      书籍 ID
     * @param engine      引擎标识（可为 random）
     * @param force       true 表示忽略已有结论强制重判
     * @param sample      取样规则；空或非法值走默认（前 10 章）
     * @param sampleCount 历史参数（三档规则章数固定，已忽略）
     * @param rerun       true 表示用户在行内点了模型名手动重跑，流水 kind 记为 rerun
     * @return 判定结论
     */
    @Operation(summary = "免费引擎判定单本")
    @PostMapping("/free/{bookId}")
    public ApiResponse<AiDetectService.FreeDetectResult> detectFree(@PathVariable String bookId, @RequestParam String engine,
                                          @RequestParam(defaultValue = "false") boolean force,
                                          @RequestParam(required = false) String sample,
                                          @RequestParam(defaultValue = "0") int sampleCount,
                                          @RequestParam(defaultValue = "false") boolean rerun) {
        log.info("请求免费初筛: bookId={}, engine={}, force={}, sample={}, sampleCount={}, rerun={}",
                bookId, engine, force, sample, sampleCount, rerun);
        // 登记「正在审查」：判定是同步请求，库里只有落下的结论，审查记录页要靠这条内存记录显示在飞状态。
        // finally 收尾，异常路径也不会留下僵尸条目
        String runningId = reviewActivity.begin(ReviewActivity.Kind.DETECT, bookId, engine,
                force ? "强制重判" : "整本初筛");
        try {
            AiDetectService.FreeDetectResult result =
                    aiDetectService.detectFree(bookId, engine, force, SampleSpec.of(sample, sampleCount), rerun);
            return ApiResponse.data(describe(result), result);
        } finally {
            reviewActivity.end(runningId);
        }
    }

    /**
     * 免费引擎批量初筛（手动按钮与前端分批循环共用）。
     *
     * @param limit       本次最多处理本数，0 表示用配置的默认值
     * @param engine      引擎标识（不支持 random）
     * @param sample      取样策略，整批共用
     * @param sampleCount 取样章数，仅非默认策略生效
     * @return 批量结果
     */
    @Operation(summary = "免费引擎批量初筛")
    @PostMapping("/sweep")
    public ApiResponse<AiDetectService.SweepResult> sweepFree(@RequestParam(defaultValue = "0") int limit,
                                         @RequestParam String engine,
                                         @RequestParam(required = false) String sample,
                                         @RequestParam(defaultValue = "0") int sampleCount) {
        log.info("请求免费批量初筛: limit={}, engine={}, sample={}, sampleCount={}",
                limit, engine, sample, sampleCount);
        // 批量没有单本书，bookId 留空；detail 记本次本数，页面上看得出"这一批多大"
        String runningId = reviewActivity.begin(ReviewActivity.Kind.SWEEP, null, engine,
                limit > 0 ? "一批 " + limit + " 本" : "一批（默认本数）");
        try {
            AiDetectService.SweepResult result =
                    aiDetectService.sweepFree(limit, engine, SampleSpec.of(sample, sampleCount));
            String message = "本次判定 " + result.processed() + " 本（AI " + result.aiCount()
                    + " / 疑似 " + result.suspectCount() + "），该引擎剩余 " + result.remaining() + " 本未判";
            if (result.failed() > 0) {
                message += "，" + result.failed() + " 本失败";
            }
            return ApiResponse.data(message, result);
        } finally {
            reviewActivity.end(runningId);
        }
    }

    /**
     * 批量取逐引擎结论：当前视图的书一次查全，前端渲染徽章用。
     *
     * @param bookIds 逗号分隔的书籍 ID
     * @return {bookId: [结论...]}
     */
    @Operation(summary = "批量查询逐引擎结论")
    @GetMapping("/verdicts")
    public ApiResponse<Map<String, List<AiDetectService.EngineVerdict>>> verdicts(@RequestParam(required = false) String bookIds) {
        List<String> ids = splitBookIds(bookIds);
        if (ids.size() > VERDICT_QUERY_LIMIT) {
            throw new IllegalArgumentException("单次最多查询 " + VERDICT_QUERY_LIMIT + " 本书的引擎结论");
        }
        List<AiDetectService.EngineVerdict> verdicts = aiDetectService.verdictsForBooks(ids);
        Map<String, List<AiDetectService.EngineVerdict>> grouped = new LinkedHashMap<>();
        for (AiDetectService.EngineVerdict verdict : verdicts) {
            grouped.computeIfAbsent(verdict.bookId(), key -> new ArrayList<>()).add(verdict);
        }
        return ApiResponse.data("success", grouped);
    }

    /**
     * 单本书的判定流水：详情面板展开「多次校验」用。
     *
     * <p>徽章按「每引擎最新一条」展示，被覆盖掉的历史只有这里能看到：
     * 哪一轮换了型号、哪一轮是逐章、每次的分数与理由各自是多少。</p>
     *
     * @param bookId 书籍 ID
     * @return 流水列表（新的在前）
     */
    @Operation(summary = "查询单本书的判定流水")
    @GetMapping("/verdicts/history")
    public ApiResponse<List<AiDetectService.VerdictLogEntry>> verdictHistory(@RequestParam String bookId) {
        return ApiResponse.list(aiDetectService.verdictHistory(bookId));
    }

    /**
     * 逐章校验推进一步：按取样条件取章节（默认前 10 章），一次判定一章。
     *
     * <p>页面点一次按钮会把整批跑完（前端 {@code runVerifyBatch} 循环调本接口），
     * 服务端仍保持"一步一章"：一批 10 章的模型调用要 30–60 秒，单请求期间没有进度，
     * 断网还会整批白跑；拆开后进度能实时显示，且游标在库里，中途关页面不会丢进度。</p>
     *
     * <p>同一模型同一章节不会重复校验；命中 AI 不再终止，会继续把剩余章节查完，
     * 每一轮都写进判定流水，详情面板按章展示。{@code allDone=true} 表示型号池已无可推进的型号。</p>
     *
     * @param bookId      书籍 ID
     * @param engine      已配置 Key 的引擎标识
     * @param sample      取样策略，空走默认（前 10 章）
     * @param sampleCount 取样章数，仅显式策略生效
     * @return 本次校验结果
     */
    @Operation(summary = "逐章校验推进一步")
    @PostMapping("/verify/{bookId}")
    public ApiResponse<AiDetectService.VerifyStepResult> stepVerify(@PathVariable String bookId,
                                                                   @RequestParam String engine,
                                                                   @RequestParam(required = false) String sample,
                                                                   @RequestParam(defaultValue = "0") int sampleCount) {
        log.info("请求逐章校验: bookId={}, engine={}, sample={}, sampleCount={}",
                bookId, engine, sample, sampleCount);
        String runningId = reviewActivity.begin(ReviewActivity.Kind.VERIFY, bookId, engine, "按取样规则逐章校验");
        try {
            AiDetectService.VerifyStepResult result =
                    aiDetectService.stepVerify(bookId, engine, SampleSpec.of(sample, sampleCount));
            return ApiResponse.data(result.message(), result);
        } finally {
            reviewActivity.end(runningId);
        }
    }

    /** 把判定结论翻成一句人话，前端直接显示 message 即可。 */
    private String describe(AiDetectService.FreeDetectResult result) {
        String label = switch (result.aiFlag()) {
            case 2 -> "AI";
            case 3 -> "疑似 AI";
            default -> "非 AI";
        };
        String suffix = result.cached() ? "（复用已有结论，未重新判定）" : "";
        return result.engine() + " 初筛完成：" + label + "（概率 " + result.aiScore() + "%）" + suffix;
    }

    /** 逗号分隔的 bookId 拆分与去空。 */
    private List<String> splitBookIds(String bookIds) {
        if (bookIds == null || bookIds.isBlank()) {
            return List.of();
        }
        return Arrays.stream(bookIds.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }
}
