package org.example.fqxs.controller;

import jakarta.validation.Valid;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.model.AiFaultClearRequest;
import org.example.fqxs.service.AiEngineRegistry;
import org.example.fqxs.service.AiFaultGuard;
import org.example.fqxs.util.DateUtil;
import org.example.fqxs.web.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 失败日志查询与自动停用的解除。
 *
 * <p>读侧 {@code GET /api/ai/faults} 按方法放行（与其它 GET 一样不进写令牌面），
 * 写侧解除停用是 POST，受 {@code X-Rank-Token} 保护。</p>
 */
@RestController
@RequestMapping(value = "/api/ai", produces = MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
public class AiFaultController {

    /** 查询窗口上限：30 天，再久的历史去库里翻没有页面意义。 */
    private static final int MAX_WINDOW_HOURS = 24 * 30;

    /** 单次返回条数上限：失败片段每条可达千字，窗口内全量返回会拖垮页面。 */
    private static final int MAX_LIMIT = 1000;

    private final AiFaultGuard faultGuard;
    private final AiEngineRegistry engineRegistry;

    public AiFaultController(AiFaultGuard faultGuard, AiEngineRegistry engineRegistry) {
        this.faultGuard = faultGuard;
        this.engineRegistry = engineRegistry;
    }

    /**
     * 失败日志。
     *
     * @param hours  时间窗口（小时），默认 72，钳到 1–720
     * @param engine 只看某个引擎；空表示全部
     * @param limit  最多返回多少条，钳到 1–1000
     * @return 窗口内失败清单 + 当前停用中的引擎
     */
    @GetMapping("/faults")
    public ApiResponse<Map<String, Object>> faults(@RequestParam(defaultValue = "72") int hours,
                                                   @RequestParam(required = false) String engine,
                                                   @RequestParam(defaultValue = "300") int limit) {
        int safeHours = Math.max(1, Math.min(hours, MAX_WINDOW_HOURS));
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        LocalDateTime from = DateUtil.now().minusHours(safeHours);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", from.toString());
        payload.put("hours", safeHours);
        payload.put("faults", faultGuard.recent(engine, from, safeLimit));
        payload.put("suspended", engineRegistry.suspendedEngines());
        // 按类型统计只覆盖"本次返回的这批"，窗口内更多时页面会标注截断
        payload.put("kindTally", faultGuard.tallyByKind(engine, from, safeLimit));
        payload.put("truncated", safeLimit >= MAX_LIMIT);
        return ApiResponse.data("success", payload);
    }

    /**
     * 手动解除某引擎的自动停用（不等停用时长自然到期）。
     *
     * @param request 引擎标识
     * @return 仅状态
     */
    @PostMapping("/faults/clear")
    public ApiResponse<Void> clear(@Valid @RequestBody AiFaultClearRequest request) {
        String engine = request.engine();
        if (engineRegistry.find(engine).isEmpty()) {
            throw new CrawlException("未知引擎: " + engine);
        }
        faultGuard.clearSuspension(engine);
        return ApiResponse.ok("已解除停用，下一次判定就会用上这个引擎");
    }
}
