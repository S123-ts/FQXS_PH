package org.example.fqxs.service;

import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.web.dto.UsageSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 调用用量记录：每次模型调用异步写一行明细，查询时按日/按引擎聚合。
 *
 * <p>不预聚合（避免并发累加的锁与冲突）；失败只记 debug 日志，绝不影响判定。
 * 只记录 token 与调用次数，不臆造成本——各平台价格会变，面板只展示数字，
 * 免费/付费判断仍走 {@link FreeModelCatalog}。</p>
 */
@Slf4j
@Component
public class AiUsageRecorder {

    private final JdbcTemplate jdbcTemplate;

    /** 单线程异步落库：判定主链路不为统计等待。 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ai-usage-recorder");
        thread.setDaemon(true);
        return thread;
    });

    public AiUsageRecorder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 记录一次模型调用。
     *
     * @param engine           引擎标识
     * @param model            型号
     * @param promptId         提示词变体（可为 null）
     * @param promptTokens     输入 token
     * @param completionTokens 输出 token
     */
    public void record(String engine, String model, String promptId, int promptTokens, int completionTokens) {
        if (engine == null || engine.isBlank()) {
            return;
        }
        executor.execute(() -> {
            try {
                jdbcTemplate.update(
                        "INSERT INTO ai_usage (usage_time, engine, model, prompt_id, prompt_tokens, completion_tokens)"
                                + " VALUES (?, ?, ?, ?, ?, ?)",
                        Timestamp.valueOf(LocalDateTime.now()), engine,
                        model == null ? "" : model,
                        promptId == null ? "" : promptId,
                        Math.max(0, promptTokens), Math.max(0, completionTokens));
            } catch (RuntimeException exception) {
                log.debug("用量记录失败（忽略）: {}", exception.getMessage());
            }
        });
    }

    /**
     * 最近 N 天的用量汇总。
     *
     * @param days 天数（调用方已钳制）
     * @return 按日 / 按引擎 / 合计的用量
     */
    public UsageSummary summary(int days) {
        List<Map<String, Object>> dailyRows = jdbcTemplate.queryForList(
                "SELECT CAST(usage_time AS DATE) AS d, COUNT(*) AS calls,"
                        + " COALESCE(SUM(prompt_tokens), 0) AS pt, COALESCE(SUM(completion_tokens), 0) AS ct"
                        + " FROM ai_usage WHERE usage_time >= DATEADD('DAY', ?, CURRENT_TIMESTAMP)"
                        + " GROUP BY CAST(usage_time AS DATE) ORDER BY d",
                -Math.max(1, days));
        List<Map<String, Object>> engineRows = jdbcTemplate.queryForList(
                "SELECT engine, COUNT(*) AS calls,"
                        + " COALESCE(SUM(prompt_tokens), 0) AS pt, COALESCE(SUM(completion_tokens), 0) AS ct"
                        + " FROM ai_usage WHERE usage_time >= DATEADD('DAY', ?, CURRENT_TIMESTAMP)"
                        + " GROUP BY engine ORDER BY calls DESC",
                -Math.max(1, days));

        // H2 的列名是大写，这里统一成小写契约，前端不必猜大小写
        List<UsageSummary.Day> daily = new java.util.ArrayList<>();
        long calls = 0;
        long promptTokens = 0;
        long completionTokens = 0;
        for (Map<String, Object> row : dailyRows) {
            long rowCalls = ((Number) row.get("CALLS")).longValue();
            long prompt = ((Number) row.get("PT")).longValue();
            long completion = ((Number) row.get("CT")).longValue();
            daily.add(new UsageSummary.Day(String.valueOf(row.get("D")), rowCalls, prompt, completion));
            calls += rowCalls;
            promptTokens += prompt;
            completionTokens += completion;
        }
        List<UsageSummary.Engine> byEngine = new java.util.ArrayList<>();
        for (Map<String, Object> row : engineRows) {
            byEngine.add(new UsageSummary.Engine(
                    String.valueOf(row.get("ENGINE")),
                    ((Number) row.get("CALLS")).longValue(),
                    ((Number) row.get("PT")).longValue(),
                    ((Number) row.get("CT")).longValue()));
        }
        return new UsageSummary(daily, byEngine,
                new UsageSummary.Totals(calls, promptTokens, completionTokens));
    }
}
