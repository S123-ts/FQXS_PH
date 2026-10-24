package org.example.fqxs.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.client.ThirdPartyBookClient;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.entity.Novel;
import org.example.fqxs.repository.NovelRepository;
import org.example.fqxs.util.DateUtil;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 作者性别 / 评分的小批量补全。
 *
 * <p>第三方 /info 是共享云手机、约 19KB/本，容量很小，所以这里刻意做三重限制：</p>
 * <ol>
 *   <li>范围：只补当前榜单按热度去重后的前 N 本（默认 50）；</li>
 *   <li>批量：每次最多 {@code book-meta-batch-max} 本（默认上限 10，前端默认 5）；</li>
 *   <li>节拍：严格串行，每本之间固定间隔（默认 2 秒），连续失败 3 本就停。</li>
 * </ol>
 *
 * <p>性别只取一次（有 checked_time 就不再请求）；评分按 {@code rating-refresh-days}
 * 刷新（默认 10 天）。同一本书在多个分类各有一行，写回时按 bookId 全行联动。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookMetaEnricher {

    /** 连续失败多少本停止本次补全：线路挂了时继续打只会更糟。 */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final NovelRepository novelRepository;
    private final ThirdPartyBookClient bookClient;
    private final AiDetectConfig aiDetectConfig;

    /** 同一时刻只允许一个补全任务，避免连点把线路打满。 */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 本次补全结果。
     *
     * @param updated   成功更新几本
     * @param failed    失败几本
     * @param remaining 前 N 本中仍待补的数量
     * @param requested 本次计划请求几本
     */
    public record Result(int updated, int failed, long remaining, int requested) {
    }

    /**
     * 补一小批作者信息。
     *
     * @param limit    本次最多几本；服务端再钳到配置上限
     * @param rankType 当前榜单分区（read/new）
     * @return 本次结果
     * @throws IllegalStateException    已有任务在进行
     * @throws IllegalArgumentException 第三方通道未启用
     */
    public Result enrich(int limit, String rankType) {
        if (!aiDetectConfig.isThirdPartyEnabled()) {
            throw new IllegalArgumentException("第三方通道未启用，无法补作者信息");
        }
        int batch = Math.max(1, Math.min(limit, Math.max(1, aiDetectConfig.getBookMetaBatchMax())));
        if (!lock.tryLock()) {
            throw new IllegalStateException("正在补充作者信息，请稍候再试");
        }
        try {
            List<Novel> pending = pendingTopBooks(rankType);
            List<Novel> targets = pending.stream().limit(batch).toList();
            int updated = 0;
            int failed = 0;
            int consecutiveFailures = 0;
            for (int i = 0; i < targets.size(); i++) {
                Novel candidate = targets.get(i);
                try {
                    ThirdPartyBookClient.BookMeta meta = bookClient.fetchMeta(candidate.getBookId());
                    updateRows(candidate.getBookId(), meta);
                    updated++;
                    consecutiveFailures = 0;
                } catch (RuntimeException failure) {
                    failed++;
                    consecutiveFailures++;
                    log.warn("作者信息补全失败: bookId={}, 原因={}", candidate.getBookId(), failure.getMessage());
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        log.warn("连续 {} 本失败，停止本次补全（线路可能不可用）", consecutiveFailures);
                        break;
                    }
                }
                if (i < targets.size() - 1) {
                    sleepBetweenRequests();
                }
            }
            // 重新查一次，失败的书仍算待补，数字才对得上
            long remaining = pendingTopBooks(rankType).size();
            log.info("作者信息补全完成: 更新 {} / 失败 {} / 待补 {} / 本次请求 {}", updated, failed, remaining,
                    targets.size());
            return new Result(updated, failed, remaining, targets.size());
        } finally {
            lock.unlock();
        }
    }

    /** 前 N 本里还需要请求的书：性别没取过，或评分没取过/已过刷新周期。 */
    private List<Novel> pendingTopBooks(String rankType) {
        LocalDateTime threshold = DateUtil.now()
                .minusDays(Math.max(1, aiDetectConfig.getRatingRefreshDays()));
        int topRank = Math.max(1, aiDetectConfig.getBookMetaTopRank());
        return novelRepository.findMetaTopBooks(rankType, PageRequest.of(0, topRank)).stream()
                .filter(novel -> novel.getAuthorGenderCheckedTime() == null
                        || novel.getRatingCheckedTime() == null
                        || novel.getRatingCheckedTime().isBefore(threshold))
                .toList();
    }

    private void updateRows(String bookId, ThirdPartyBookClient.BookMeta meta) {
        List<Novel> rows = novelRepository.findAllByBookId(bookId);
        if (rows.isEmpty()) {
            return;
        }
        LocalDateTime now = DateUtil.now();
        for (Novel row : rows) {
            // 性别只取一次：已经取过就保留首次结果，评分刷新时也不覆盖
            if (row.getAuthorGenderCheckedTime() == null) {
                row.setAuthorGender(meta.authorGender());
                row.setAuthorGenderCheckedTime(now);
            }
            if (meta.rating() != null) {
                row.setRating(meta.rating());
            }
            row.setRatingCheckedTime(now);
        }
        novelRepository.saveAll(rows);
    }

    private void sleepBetweenRequests() {
        long interval = Math.max(0, aiDetectConfig.getBookMetaIntervalMillis());
        if (interval == 0) {
            return;
        }
        try {
            Thread.sleep(interval);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
