package org.example.fqxs.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.example.fqxs.service.RecordQueryService;
import org.example.fqxs.web.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审查记录页的数据接口（只读）。
 *
 * <p>页面在 {@code /records}（{@code records.html}），三块内容一次取全：
 * 正在审查（内存态）、章节拉取（{@code novel_chapter}）、判定结果（{@code novel_ai_verdict_log}）。</p>
 *
 * <p>路径用 {@code /api/records} 而不是挂到 {@code /api/ai} 下：这一页同时讲抓正文与判 AI，
 * 挂在 AI 前缀下名不副实。全部是 GET，进不了写令牌面（{@code X-Rank-Token} 只管
 * {@code /api/rank/**} 与 {@code /api/ai/**} 的写操作），但仍在登录门之内。</p>
 */
@Tag(name = "审查记录")
@RestController
@RequestMapping(value = "/api/records", produces = MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
@RequiredArgsConstructor
public class RecordController {

    /** 时间窗口上限：30 天，再久的历史在页面上没有意义（流水每本只留 60 轮）。 */
    private static final int MAX_WINDOW_HOURS = 24 * 30;

    /** 每块最多返回多少条。 */
    private static final int MAX_LIMIT = 500;

    private final RecordQueryService recordQueryService;

    /**
     * 审查记录总览。
     *
     * @param hours 时间窗口（小时），默认 24，钳到 1–720
     * @param limit 每块最多多少条，默认 200，钳到 1–500
     * @return 正在审查 + 章节拉取 + 判定结果 + 结论分布
     */
    @Operation(summary = "审查记录总览")
    @GetMapping
    public ApiResponse<RecordQueryService.Overview> overview(@RequestParam(defaultValue = "24") int hours,
                                                             @RequestParam(defaultValue = "200") int limit) {
        int safeHours = Math.max(1, Math.min(hours, MAX_WINDOW_HOURS));
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        return ApiResponse.data("success", recordQueryService.overview(safeHours, safeLimit));
    }
}
