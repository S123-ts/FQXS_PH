package org.example.fqxs.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.example.fqxs.logging.LogQueryService;
import org.example.fqxs.web.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 日志查询接口（只读）：三类日志各一个入口，外加一个运行态。
 *
 * <p>为什么不挂在 {@code /api/ai} 下：日志是整站级的观测面，与 AI 判定无关；
 * 也不进 {@code /api/db}（那是带管理令牌的删库面）。这几个都是 GET，
 * 只受登录门保护，与审查记录页同一档。</p>
 *
 * <p>三个入口对应三张表，没有「统一日志」入口：{@code app_log} 的 message 与
 * {@code api_log} 的 target 语义完全不同，硬拼成一张宽表只会让两边都难用。</p>
 */
@Tag(name = "日志")
@RestController
@RequestMapping(value = "/api/logs", produces = MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
@RequiredArgsConstructor
public class LogController {

    private final LogQueryService logQueryService;

    /**
     * 程序日志（全量镜像）。
     *
     * @param level  级别，如 ERROR / WARN，留空不过滤
     * @param bookId 只看某本书的日志，留空不过滤
     * @param limit  最多返回多少条，默认 200，按配置上限钳
     */
    @Operation(summary = "程序日志")
    @GetMapping("/app")
    public ApiResponse<List<Map<String, Object>>> appLogs(@RequestParam(required = false) String level,
                                                          @RequestParam(required = false) String bookId,
                                                          @RequestParam(defaultValue = "200") int limit) {
        return ApiResponse.data("success", logQueryService.recentAppLogs(level, bookId, clamp(limit)));
    }

    /**
     * API 日志。
     *
     * @param direction IN = 本系统对外接口，OUT = 打向番茄官方接口；留空两种都要
     * @param bookId    只看某本书，留空不过滤
     * @param limit     最多返回多少条
     */
    @Operation(summary = "API 日志（入站 / 官方出站）")
    @GetMapping("/api")
    public ApiResponse<List<Map<String, Object>>> apiLogs(@RequestParam(required = false) String direction,
                                                          @RequestParam(required = false) String bookId,
                                                          @RequestParam(defaultValue = "200") int limit) {
        return ApiResponse.data("success", logQueryService.recentApiLogs(direction, bookId, clamp(limit)));
    }

    /**
     * 第三方抽取日志。
     *
     * @param line    线路地址，如 https://fq-book.netsite.cc；留空不过滤
     * @param bookId  只看某本书，留空不过滤
     * @param outcome OK / HTTP_ERROR / EMPTY / IO_ERROR / PARSE_ERROR / ITEM_UNAVAILABLE；留空不过滤
     * @param limit   最多返回多少条
     */
    @Operation(summary = "第三方抽取日志")
    @GetMapping("/third-party")
    public ApiResponse<List<Map<String, Object>>> thirdPartyLogs(@RequestParam(required = false) String line,
                                                                 @RequestParam(required = false) String bookId,
                                                                 @RequestParam(required = false) String outcome,
                                                                 @RequestParam(defaultValue = "200") int limit) {
        return ApiResponse.data("success",
                logQueryService.recentThirdPartyLogs(line, bookId, outcome, clamp(limit)));
    }

    /** 运行态：开关、队列积压、累计丢弃量。写库卡住时靠它判断「是没日志还是没写进去」。 */
    @Operation(summary = "日志落库运行态")
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.data("success", logQueryService.status());
    }

    private int clamp(int limit) {
        return Math.max(1, Math.min(limit, logQueryService.maxRows()));
    }
}
