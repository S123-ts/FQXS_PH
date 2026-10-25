package org.example.fqxs.bootstrap;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.service.BookMetaEnricher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 启动后台补齐线程：应用起来后开一个命名守护线程，按「一分钟一本」的线性节拍
 * （{@code book-meta-backfill-per-hour}，默认 60）从待补清单**队首按热度降序**逐本推进。
 *
 * <p>节拍只有一条：每本之间停 3600000 / perHour 毫秒，没有批内、批间两套停顿。
 * 一轮推进 perHour 本（默认 60 本 ≈ 一小时），轮末重取待补清单，新上榜的书与评分到期的书
 * 由此轮进来。</p>
 *
 * <p>为什么按队首而不是随机抽：待补 4000+ 本时随机抽会让单本期望要等一天才轮到，
 * 而页面默认视图看的正是热度前排。队首推进下前 50 本一小时内就有数据。</p>
 *
 * <p>与手动「补作者信息」共用同一把锁：抢不到就让路（按手动路径的
 * {@code book-meta-interval-millis} 重试，上限 {@value #MAX_YIELDS_PER_ROUND} 次），
 * 绝不和手动路径抢线路。连续 3 本失败按线路不可用处理，歇 10 分钟再开下一轮；
 * 没有待补的书时每小时复查一次。</p>
 *
 * <p>失败分两类：已下架（{@code SKIPPED}）只盖章离开待补队列、不计失败；线路冷却中
 * （{@code COOLDOWN}）本轮立即收尾、只等一个节拍，绝不把「线路在休息」记成「线路坏了」。</p>
 *
 * <p>Runner 只提交任务立即返回，不把补齐时间算进启动；线程内所有异常自己兜住，
 * 绝不影响启动（同 {@link StartupDataInitializer} 的契约）。</p>
 */
@Slf4j
@Component
@Order(104) // 晚于建表/回填/播种/备份：只读已就位的数据，不参与启动顺序
public class BookMetaBackfillRunner implements ApplicationRunner {

    /** 连续失败几本判定线路不可用：弱线路上继续打只会更糟，歇一会儿再开下一轮。 */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    /** 线路不可用 / 本轮异常后的歇息时长。 */
    private static final long LINE_DOWN_PAUSE_MILLIS = TimeUnit.MINUTES.toMillis(10);

    /** 没有待补的书时多久复查一次。 */
    private static final long IDLE_POLL_MILLIS = TimeUnit.HOURS.toMillis(1);

    /** 让路（锁被手动路径占用）累计超过这个次数就结束本轮，避免手动长时间批量时后台空转。包级可见供单测。 */
    static final int MAX_YIELDS_PER_ROUND = 10;

    /** 节拍上限：每小时最多 360 本（= 每本至少 10 秒）。再快就超出共享线路的承受能力，配置写大了也按它钳。 */
    static final int MAX_PER_HOUR = 360;

    private final BookMetaEnricher bookMetaEnricher;
    private final AiDetectConfig aiDetectConfig;

    /**
     * 单测用的节拍覆盖：{@code >= 0} 时直接用它当每本间隔，避免真的等一分钟；
     * {@code -1} 表示按配置换算（生产路径）。
     */
    private final long perBookMillisOverride;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile ExecutorService executor;

    @Autowired
    public BookMetaBackfillRunner(BookMetaEnricher bookMetaEnricher, AiDetectConfig aiDetectConfig) {
        this(bookMetaEnricher, aiDetectConfig, -1);
    }

    /** 包级可见供单测：第三个参数把节拍压到 0，不碰真实等待。 */
    BookMetaBackfillRunner(BookMetaEnricher bookMetaEnricher, AiDetectConfig aiDetectConfig,
                           long perBookMillisOverride) {
        this.bookMetaEnricher = bookMetaEnricher;
        this.aiDetectConfig = aiDetectConfig;
        this.perBookMillisOverride = perBookMillisOverride;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!aiDetectConfig.isBookMetaBackfillEnabled()) {
            log.info("启动后台补齐已关闭（book-meta-backfill-enabled=false），作者性别/评分仍可手动补");
            return;
        }
        if (!aiDetectConfig.isThirdPartyEnabled()) {
            log.info("第三方通道未启用，跳过启动后台补齐");
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "book-meta-backfill");
            thread.setDaemon(true);
            return thread;
        });
        executor.submit(this::loop);
        log.info("启动后台补齐线程已开启: 每 {} 秒一本（{} 本/小时），按热度序逐本推进",
                TimeUnit.MILLISECONDS.toSeconds(perBookMillis()), perHour());
    }

    @PreDestroy
    public void stop() {
        ExecutorService pool = executor;
        if (pool != null) {
            // 中断睡眠中的节拍等待，别让关闭流程陪一次睡眠走完
            pool.shutdownNow();
        }
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                RoundStats stats = backfillRound();
                if (stats.lineDown()) {
                    if (!waitQuietly(LINE_DOWN_PAUSE_MILLIS)) {
                        return;
                    }
                } else if (stats.coolingDown()) {
                    // 线路在冷却：等到下一个节拍即可，别叠加 10 分钟停顿
                    if (!waitQuietly(perBookMillis())) {
                        return;
                    }
                } else if (stats.attempted() == 0 && stats.yielded() == 0) {
                    // 待补清单空了：评分到期的书靠每小时复查轮进来
                    if (!waitQuietly(IDLE_POLL_MILLIS)) {
                        return;
                    }
                } else if (stats.attempted() == 0) {
                    // 整轮都在让路（手动路径占着线路）：等一个节拍再重开，不空转
                    if (!waitQuietly(perBookMillis())) {
                        return;
                    }
                }
                // 其余情况（本轮正常推进）不额外睡：轮内已经铺满 perHour 个节拍，直接重取队列接着推
            } catch (RuntimeException exception) {
                log.error("启动后台补齐本轮失败（数据库或线路不可用？），歇一会儿重试: {}",
                        exception.getMessage());
                if (!waitQuietly(LINE_DOWN_PAUSE_MILLIS)) {
                    return;
                }
            }
        }
    }

    /**
     * 一轮 = 取待补清单队首最多 {@link #perHour()} 本，按热度序逐本串行请求，
     * 每本之后停一个节拍；连续失败 3 本立刻收尾（lineDown），让路次数超限也收尾。
     * 包级可见，单测直接调用、不碰真实线路与长睡眠。
     */
    RoundStats backfillRound() {
        List<String> pending = bookMetaEnricher.pendingMetaBookIds();
        if (pending.isEmpty()) {
            return new RoundStats(0, 0, 0, 0, 0, false, false);
        }
        List<String> batch = headOfQueue(pending);
        log.info("后台补齐本轮: 待补 {} 本，本轮推进 {} 本，节拍 {} 秒/本",
                pending.size(), batch.size(), TimeUnit.MILLISECONDS.toSeconds(perBookMillis()));
        int updated = 0;
        int failed = 0;
        int skipped = 0;
        int yields = 0;
        int consecutiveFailures = 0;
        int index = 0;
        while (index < batch.size() && !Thread.currentThread().isInterrupted()) {
            String bookId = batch.get(index);
            BookMetaEnricher.Outcome outcome = bookMetaEnricher.enrichOne(bookId);
            if (outcome == BookMetaEnricher.Outcome.LOCKED) {
                yields++;
                if (yields > MAX_YIELDS_PER_ROUND) {
                    log.info("手动补全长时间占用线路，本轮让路收尾（已补 {} 本），稍后重开", updated);
                    return new RoundStats(updated + failed + skipped, updated, failed, skipped, yields, false, false);
                }
                log.info("手动补全正在进行，后台补齐让路: bookId={}", bookId);
                if (!waitQuietly(yieldMillis())) {
                    return new RoundStats(updated + failed + skipped, updated, failed, skipped, yields, false, false);
                }
                continue;
            }
            if (outcome == BookMetaEnricher.Outcome.COOLDOWN) {
                // 线路冷却中：本轮立即收尾，由 loop 决定等多久，绝不记成失败
                log.info("第三方线路冷却中，本轮收尾: 已补 {} 本", updated);
                return new RoundStats(updated + failed + skipped, updated, failed, skipped, yields, false, true);
            }
            index++;
            if (outcome == BookMetaEnricher.Outcome.UPDATED) {
                updated++;
                consecutiveFailures = 0;
            } else if (outcome == BookMetaEnricher.Outcome.SKIPPED) {
                // 书已下架：已盖章离开待补队列，不算线路失败
                skipped++;
                consecutiveFailures = 0;
            } else {
                failed++;
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    log.warn("连续 {} 本失败，本轮收尾（线路可能不可用），{} 分钟后重开",
                            consecutiveFailures, TimeUnit.MILLISECONDS.toMinutes(LINE_DOWN_PAUSE_MILLIS));
                    return new RoundStats(updated + failed + skipped, updated, failed, skipped, yields, true, false);
                }
            }
            // 最后一本之后也睡：下一轮紧接着从队首重取，轮与轮之间同样要守住节拍
            if (!waitQuietly(perBookMillis())) {
                return new RoundStats(updated + failed + skipped, updated, failed, skipped, yields, false, false);
            }
        }
        log.info("后台补齐本轮结束: 更新 {}, 跳过 {}, 失败 {}, 让路 {}", updated, skipped, failed, yields);
        return new RoundStats(updated + failed + skipped, updated, failed, skipped, yields, false, false);
    }

    /** 取队首：待补清单已按热度降序，取前 perHour 本，本轮铺满一小时。 */
    private List<String> headOfQueue(List<String> pending) {
        int size = Math.min(perHour(), pending.size());
        return new ArrayList<>(pending.subList(0, size));
    }

    /** 每小时补几本：配置写大了按 {@link #MAX_PER_HOUR} 钳。包级可见供单测。 */
    int perHour() {
        return Math.min(Math.max(1, aiDetectConfig.getBookMetaBackfillPerHour()), MAX_PER_HOUR);
    }

    /** 每本之间的节拍 = 一小时 / perHour（默认 60 本/小时 = 一分钟一本）。 */
    private long perBookMillis() {
        if (perBookMillisOverride >= 0) {
            return perBookMillisOverride;
        }
        return TimeUnit.HOURS.toMillis(1) / perHour();
    }

    /** 让路时的重试间隔：跟手动路径的 2 秒间隔走，不按补齐节拍干等一分钟。 */
    private long yieldMillis() {
        return Math.max(0, aiDetectConfig.getBookMetaIntervalMillis());
    }

    /** 睡指定时长；被中断（应用关闭）时恢复中断标记并返回 false。 */
    private boolean waitQuietly(long millis) {
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 一轮的结果，attempted = updated + failed + skipped（让路不计）。 */
    record RoundStats(int attempted, int updated, int failed, int skipped, int yielded,
                      boolean lineDown, boolean coolingDown) {
    }
}
