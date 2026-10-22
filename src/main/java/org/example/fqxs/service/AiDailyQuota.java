package org.example.fqxs.service;

import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.client.CallQuota;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.exception.QuotaExceededException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日模型调用上限（安全测试报告 M1）：数 {@code ai_usage} 当天有多少行，到顶就拒绝下一次调用。
 *
 * <p>为什么数行数而不是维护一个计数器：{@code ai_usage} 已经是「一次真实出网调用一行」的明细
 * （{@code AiUsageRecorder} 异步写），再存一份计数只会多一个会漂移的真值。
 * 代价是每次判定前一次 COUNT——表在万级行数内是毫秒级，且这里带了 2 秒记忆。</p>
 *
 * <p><b>失败放行</b>：库不可用（文件被锁、迁移没跑）时 {@code count} 会抛 {@code DataAccessException}，
 * 这里吞掉并放行。配额是「省钱」的护栏，不是安全边界；让它把整条判定链路变成 503 是本末倒置。</p>
 */
@Slf4j
@Component
public class AiDailyQuota implements CallQuota {

    /** 计数记忆时长：一次批量初筛会连着调十几次，没必要每次都扫表。 */
    private static final long CACHE_MILLIS = 2000L;

    private final JdbcTemplate jdbcTemplate;

    private final AiDetectConfig aiDetectConfig;

    private long cachedCount = -1L;

    private long cachedAt;

    public AiDailyQuota(JdbcTemplate jdbcTemplate, AiDetectConfig aiDetectConfig) {
        this.jdbcTemplate = jdbcTemplate;
        this.aiDetectConfig = aiDetectConfig;
    }

    @Override
    public void requireAvailable() {
        int limit = aiDetectConfig.getDailyCallLimit();
        if (limit <= 0) {
            return;
        }
        long used = todayCalls();
        if (used >= 0 && used >= limit) {
            throw new QuotaExceededException("今日 AI 调用已达上限（" + limit + " 次），"
                    + "明天再试，或调大 ai.detect.daily-call-limit / 设环境变量 AI_DAILY_CALL_LIMIT");
        }
    }

    /**
     * 今天的调用次数。
     *
     * @return 次数；读库失败返回 -1（调用方据此放行）
     */
    public synchronized long todayCalls() {
        long now = System.currentTimeMillis();
        if (cachedCount >= 0 && now - cachedAt < CACHE_MILLIS) {
            return cachedCount;
        }
        try {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_usage WHERE usage_time >= ?",
                    Long.class, Timestamp.valueOf(LocalDate.now().atStartOfDay()));
            cachedCount = count == null ? 0L : count;
        } catch (DataAccessException e) {
            // 库不可用不等于「额度用完了」：放行，让真正的判定链路去报它自己的错
            log.debug("AI 日用量读取失败，本次不设限: {}", e.getMessage());
            return -1L;
        }
        cachedAt = now;
        return cachedCount;
    }

    /** 让缓存失效：单测与「刚判完一批想立刻看新数字」的场景用。 */
    synchronized void invalidate() {
        cachedCount = -1L;
        cachedAt = 0L;
    }

    /** 当天起点（供日志与单测对齐口径）。 */
    static LocalDateTime todayStart() {
        return LocalDate.now().atStartOfDay();
    }
}
