package org.example.fqxs.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.entity.AiEngine;
import org.example.fqxs.entity.AiEngineFault;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.repository.AiEngineFaultRepository;
import org.example.fqxs.repository.AiEngineRepository;
import org.example.fqxs.util.DateUtil;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 失败留证 + 连续失败自动停用。
 *
 * <p>判定消耗额度，因此「这个模型不行」必须做到三件事：<b>留下证据</b>（写
 * {@code ai_engine_fault}，含上游状态码与返回片段）、<b>别再撞同一处</b>（同引擎连续失败
 * 累计到阈值就停用若干小时，默认 3 次 / 48 小时）、<b>能自动回来</b>（到期即可用，
 * 也可在日志页面手动解除）。停用状态写在库里而不是内存，重启不会被洗掉。</p>
 *
 * <p>计数只在<b>判定成功</b>时清零：免费渠道偶发 429 很常见，零星失败就把引擎摁死两天
 * 会让熔断候选清空、整个判定停摆。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiFaultGuard {

    /** 超时/连不上这类没有状态码的失败，靠文案识别。 */
    private static final Pattern NETWORK_HINT =
            Pattern.compile("(timed out|timeout|connection|Connection reset|请求失败)");

    private static final int REASON_LIMIT = 300;

    private static final int EXCERPT_LIMIT = 1000;

    private final AiEngineFaultRepository faultRepository;
    private final AiEngineRepository engineRepository;
    private final AiDetectConfig aiDetectConfig;

    /**
     * 记一次失败：先写事实，再按连续次数决定是否停用。
     *
     * @param engine  引擎标识
     * @param model   失败时使用的型号，可为 null
     * @param failure 失败异常，{@link CrawlException} 会带上游状态码与返回片段
     * @param bookId  失败时判定的书籍 ID，便于复现，可为 null
     */
    public void recordFailure(String engine, String model, Throwable failure, String bookId) {
        int status = failure instanceof CrawlException crawl ? crawl.httpStatus() : 0;
        String excerpt = failure instanceof CrawlException crawl ? crawl.responseExcerpt() : null;
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();

        // reason 列是 NOT NULL：getMessage() 返回空串/空白时 truncate 会给 null，
        // 插入失败会被下面的 catch 吞掉，导致留证与熔断计数一起静默失效
        String reason = truncate(message, REASON_LIMIT);
        try {
            faultRepository.save(AiEngineFault.builder()
                    .engine(engine)
                    .model(model)
                    .kind(classify(status, message))
                    .httpStatus(status > 0 ? status : null)
                    .reason(reason == null ? "(无消息)" : reason)
                    .responseExcerpt(truncate(excerpt, EXCERPT_LIMIT))
                    .bookId(bookId)
                    .occurredTime(DateUtil.now())
                    .build());
        } catch (RuntimeException storeFailure) {
            // 留证失败不能让判定链路本身崩：日志里留一条就够了
            log.warn("失败留证写入失败: engine={}, 原因={}", engine, storeFailure.getMessage());
            return;
        }
        countAndSuspend(engine, message);
    }

    /** 判定成功：清零连续失败计数，历史停用记录仍留在表里可查。 */
    public void recordSuccess(String engine) {
        engineRepository.findById(engine)
                .filter(record -> record.getConsecutiveFailures() != 0)
                .ifPresent(record -> {
                    record.setConsecutiveFailures(0);
                    engineRepository.save(record);
                });
    }

    /** 手动解除停用（日志页面上的「恢复」动作），计数一并清零。 */
    public void clearSuspension(String engine) {
        engineRepository.findById(engine).ifPresent(record -> {
            record.setDisableUntil(null);
            record.setDisableReason(null);
            record.setConsecutiveFailures(0);
            engineRepository.save(record);
            log.info("已手动解除引擎停用: {}", engine);
        });
    }

    /** 时间窗口内的失败记录，按新到旧。 */
    public List<AiEngineFault> recent(String engine, LocalDateTime from, int limit) {
        var page = org.springframework.data.domain.PageRequest.of(0, Math.max(1, limit));
        return engine == null || engine.isBlank()
                ? faultRepository.findByOccurredTimeAfterOrderByOccurredTimeDesc(from, page)
                : faultRepository.findByEngineAndOccurredTimeAfterOrderByOccurredTimeDesc(engine, from, page);
    }

    /** 按类型统计本次返回的这批失败；窗口里更多时由接口的 truncated 字段说明。 */
    public Map<String, Long> tallyByKind(String engine, LocalDateTime from, int limit) {
        Map<String, Long> tally = new LinkedHashMap<>();
        for (AiEngineFault fault : recent(engine, from, limit)) {
            tally.merge(fault.getKind(), 1L, Long::sum);
        }
        return tally;
    }

    /** 连续失败 +1；达到阈值且允许自动停用的引擎，记上到期时间。 */
    private void countAndSuspend(String engine, String message) {
        engineRepository.findById(engine).ifPresent(record -> {
            int failures = record.getConsecutiveFailures() + 1;
            record.setConsecutiveFailures(failures);
            int threshold = Math.max(1, aiDetectConfig.getFaultSuspendThreshold());
            // 停用条件不看 autoBan：autoBan 管的是"能不能被熔断自动选去用"，
            // 这里管的是"别再撞同一个坏上游"，按 token 计费的朱雀尤其需要它
            if (failures >= threshold && !record.isSuspended()) {
                int hours = Math.max(1, aiDetectConfig.getFaultSuspendHours());
                record.setDisableUntil(DateUtil.now().plusHours(hours));
                record.setDisableReason(truncate("连续 " + failures + " 次失败：" + message, 300));
                log.warn("引擎已自动停用 {} 小时: engine={}, 次数={}, 原因={}", hours, engine, failures, message);
            }
            engineRepository.save(record);
        });
    }

    /** 失败分类：有状态码按码分，没有再按文案分，最后归 unknown。 */
    static String classify(int httpStatus, String message) {
        if (httpStatus == 401 || httpStatus == 403) {
            return "auth";
        }
        if (httpStatus == 429) {
            return "rate";
        }
        if (httpStatus >= 500) {
            return "server";
        }
        String text = message == null ? "" : message;
        if (text.contains("ai_probability")) {
            return "parse";
        }
        if (text.contains("空响应")) {
            return "empty";
        }
        if (NETWORK_HINT.matcher(text).find()) {
            return "network";
        }
        return "unknown";
    }

    private static String truncate(String text, int limit) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() > limit ? flat.substring(0, limit) : flat;
    }
}
