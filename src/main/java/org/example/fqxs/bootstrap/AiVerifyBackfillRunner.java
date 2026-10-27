package org.example.fqxs.bootstrap;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.exception.LineCoolingException;
import org.example.fqxs.exception.OperationConflictException;
import org.example.fqxs.exception.QuotaExceededException;
import org.example.fqxs.model.SampleSpec;
import org.example.fqxs.repository.NovelRepository;
import org.example.fqxs.service.AiDetectService;
import org.example.fqxs.service.AiEngineRegistry;
import org.example.fqxs.service.AiVerifyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 后台逐章校验线程：把库里的书按热度序逐本推进，每个可用引擎各跑一遍前 10 章——
 * 但只在<b>家族代表型号</b>上跑（{@link AiEngineRegistry#distinctModelsOf}）：
 * {@code ernie-4.5-turbo-32k} 与 {@code -128k}、{@code glm-4} 与 {@code glm-4-flash} 这类
 * 同模型的不同包装，判定结果几乎不会有区别，各跑一遍等于把免费额度白扔一半
 * （归一规则与失败方向见 {@link org.example.fqxs.service.ModelFamily}）。手动判定不受此限。
 *
 * <p>节拍与第三方书源一致：一轮 = 每个可用引擎各推进<b>一章</b>，然后停
 * {@code 3600000 / ai-verify-backfill-per-hour} 毫秒（默认 6 → 10 分钟）。三家引擎一轮就是
 * 3 次模型调用，一天 144 轮 ≈ 432 次——免费渠道的日额度（{@code daily-call-limit}，默认 500）
 * 撑得住，但已经吃掉大半，所以节拍宁可慢、不可快。</p>
 *
 * <p>为什么一轮只推进一章而不是一本书查完：{@link AiVerifyService#stepVerify} 的契约就是
 * 「一步一章」，服务端不自己循环。逐章推进还有两个好处——单次调用只花几秒不会把线程占死，
 * 中途出错时游标已经落在库里、下次接着推。</p>
 *
 * <p>失败分三类，只有真失败才计数（口径同 {@link BookMetaBackfillRunner}）：</p>
 * <ul>
 *   <li>{@link OperationConflictException}：这本书的这个引擎正被页面手动校验 → 让路，换下一本；</li>
 *   <li>{@link QuotaExceededException}：今天额度到顶 → 本轮立即收尾，等一小时再看；</li>
 *   <li>{@link LineCoolingException}：上游线路在冷却 → 本轮收尾，只等一个节拍，绝不记成故障；</li>
 *   <li>其余异常记一次失败；同一轮里连着失败 {@value #MAX_CONSECUTIVE_FAILURES} 次就收尾歇
 *       {@value #LINE_DOWN_PAUSE_MINUTES} 分钟。<b>「报错几次后停一小时」由熔断器负责</b>：
 *       {@code AiFaultGuard} 连续失败到 {@code fault-suspend-threshold} 就把该引擎停用
 *       {@code fault-suspend-hours}（默认 1 小时），停用期间它自动不在
 *       {@link AiEngineRegistry#availableVerifyEngines()} 里，本线程无需重复计时。</li>
 * </ul>
 *
 * <p>Runner 只提交任务立即返回，线程内所有异常自己兜住，绝不影响启动。</p>
 */
@Slf4j
@Component
@Order(105) // 晚于建表/播种/作者补齐（104）：只读已就位的数据；默认档走官网正文，与补齐的第三方线路基本不重叠
public class AiVerifyBackfillRunner implements ApplicationRunner {

    /** 一轮里连续失败几次就收尾：再打下去多半是引擎或线路的问题，交给熔断器停用它。 */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    /** 收尾后的歇息时长（分钟）。引擎级的一小时停用由熔断器负责，这里只是本轮的冷静期。 */
    static final int LINE_DOWN_PAUSE_MINUTES = 10;

    private static final long LINE_DOWN_PAUSE_MILLIS = TimeUnit.MINUTES.toMillis(LINE_DOWN_PAUSE_MINUTES);

    /** 全部书都校验完 / 额度到顶后的复查间隔。 */
    private static final long IDLE_POLL_MILLIS = TimeUnit.HOURS.toMillis(1);

    /** 节拍上限：每小时最多 60 轮（= 每轮至少 1 分钟）。再快免费渠道的日额度当天就会见底。 */
    static final int MAX_PER_HOUR = 60;

    private final AiVerifyService verifyService;
    private final AiEngineRegistry engineRegistry;
    private final NovelRepository novelRepository;
    private final AiDetectConfig aiDetectConfig;

    /**
     * 单测用的节拍覆盖：{@code >= 0} 时直接用它当轮间隔，避免真的等十分钟；
     * {@code -1} 表示按配置换算（生产路径）。
     */
    private final long perRoundMillisOverride;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile ExecutorService executor;

    @Autowired
    public AiVerifyBackfillRunner(AiVerifyService verifyService, AiEngineRegistry engineRegistry,
                                  NovelRepository novelRepository, AiDetectConfig aiDetectConfig) {
        this(verifyService, engineRegistry, novelRepository, aiDetectConfig, -1);
    }

    /** 包级可见供单测：最后一个参数把节拍压到 0，不碰真实等待。 */
    AiVerifyBackfillRunner(AiVerifyService verifyService, AiEngineRegistry engineRegistry,
                           NovelRepository novelRepository, AiDetectConfig aiDetectConfig,
                           long perRoundMillisOverride) {
        this.verifyService = verifyService;
        this.engineRegistry = engineRegistry;
        this.novelRepository = novelRepository;
        this.aiDetectConfig = aiDetectConfig;
        this.perRoundMillisOverride = perRoundMillisOverride;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!aiDetectConfig.isAiVerifyBackfillEnabled()) {
            log.info("后台逐章校验已关闭（ai-verify-backfill-enabled=false），页面上的手动校验不受影响");
            return;
        }
        if (!aiDetectConfig.isEnabled()) {
            log.info("AI 判定总开关未启用，跳过后台逐章校验");
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ai-verify-backfill");
            thread.setDaemon(true);
            return thread;
        });
        executor.submit(this::loop);
        log.info("后台逐章校验线程已开启: 每 {} 分钟一轮，每轮每个可用引擎各推进一章",
                TimeUnit.MILLISECONDS.toMinutes(perRoundMillis()));
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
            long pause;
            try {
                RoundStats stats = verifyRound();
                if (stats.quotaExhausted()) {
                    pause = IDLE_POLL_MILLIS;
                } else if (stats.lineDown()) {
                    pause = LINE_DOWN_PAUSE_MILLIS;
                } else if (stats.idle()) {
                    // 在库的书都校验完了：新抓的书靠每小时复查轮进来
                    pause = IDLE_POLL_MILLIS;
                } else {
                    // 正常推进、让路、线路冷却都只等一个节拍，别叠加停顿
                    pause = perRoundMillis();
                }
            } catch (RuntimeException exception) {
                log.error("后台逐章校验本轮失败（数据库不可用？），歇一会儿重试: {}", exception.getMessage());
                pause = LINE_DOWN_PAUSE_MILLIS;
            }
            if (!waitQuietly(pause)) {
                return;
            }
        }
    }

    /**
     * 一轮 = 遍历可用引擎，每个引擎取候选队首一本、推进一章。包级可见，单测直接调用。
     *
     * <p>引擎之间不额外睡：一轮就 3 次调用、几秒内打完，然后整轮停一个节拍，
     * 这样「10 分钟三次」的额度节奏才对得上。轮内某本正在被手动校验就换下一本，
     * 让路不算失败也不算推进。</p>
     */
    RoundStats verifyRound() {
        List<Map<String, String>> engines = engineRegistry.availableVerifyEngines();
        if (engines.isEmpty()) {
            log.info("没有可用的逐章校验引擎（都未配 Key、被停用或熔断中），本轮跳过");
            return new RoundStats(0, 0, 0, 0, 0, false, false, false);
        }
        int chapters = SampleSpec.HEAD_CHAPTERS;
        int stepped = 0;
        int failed = 0;
        int yielded = 0;
        int doneEngines = 0;
        int consecutiveFailures = 0;
        for (Map<String, String> item : engines) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            String engine = item.get("id");
            // 家族代表型号：同模型的不同包装（上下文长度 / 日期快照 / -flash）只跑一个，省下的额度换产量
            List<String> familyModels = engineRegistry.distinctModelsOf(engine);
            List<String> rowModels = familyModels.stream()
                    .map(model -> model + AiVerifyService.VERIFY_MODEL_SUFFIX)
                    .toList();
            List<String> candidates = novelRepository.findBookIdsNeedingVerify(
                    engine, rowModels, familyModels.size(), chapters, PageRequest.of(0, 1));
            if (candidates.isEmpty()) {
                // 该引擎把在库的书都查完了，不是失败
                doneEngines++;
                continue;
            }
            String bookId = candidates.get(0);
            try {
                AiDetectService.VerifyStepResult result = verifyService.stepVerify(
                        bookId, engine, SampleSpec.defaultSpec(), familyModels);
                stepped++;
                consecutiveFailures = 0;
                if (result.allDone()) {
                    log.info("后台逐章校验: engine={} 的家族代表型号已查完 bookId={} 的前 {} 章",
                            engine, bookId, chapters);
                }
            } catch (OperationConflictException conflict) {
                yielded++;
                log.info("页面正在手动校验，后台让路: bookId={}, engine={}", bookId, engine);
            } catch (QuotaExceededException quota) {
                log.info("今日 AI 额度已用尽，本轮收尾: {}", quota.getMessage());
                return new RoundStats(stepped + failed, stepped, failed, yielded, doneEngines,
                        true, false, false);
            } catch (LineCoolingException cooling) {
                log.info("上游线路冷却中，本轮收尾: {}", cooling.getMessage());
                return new RoundStats(stepped + failed, stepped, failed, yielded, doneEngines,
                        false, true, false);
            } catch (RuntimeException exception) {
                failed++;
                consecutiveFailures++;
                log.warn("后台逐章校验失败({}/{}): bookId={}, engine={}, {}",
                        consecutiveFailures, MAX_CONSECUTIVE_FAILURES, bookId, engine,
                        exception.getMessage());
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    log.warn("一轮内连续失败 {} 次，本轮收尾，{} 分钟后重开",
                            consecutiveFailures, LINE_DOWN_PAUSE_MINUTES);
                    return new RoundStats(stepped + failed, stepped, failed, yielded, doneEngines,
                            false, false, true);
                }
            }
        }
        if (stepped + failed > 0) {
            log.info("后台逐章校验本轮结束: 推进 {} 章, 失败 {}, 让路 {}, 已查完的引擎 {}",
                    stepped, failed, yielded, doneEngines);
        }
        return new RoundStats(stepped + failed, stepped, failed, yielded, doneEngines,
                false, false, false);
    }

    /** 每小时几轮：配置写大了按 {@link #MAX_PER_HOUR} 钳。包级可见供单测。 */
    int perHour() {
        return Math.min(Math.max(1, aiDetectConfig.getAiVerifyBackfillPerHour()), MAX_PER_HOUR);
    }

    /** 轮间隔 = 一小时 / perHour（默认 6 轮/小时 = 10 分钟一轮）。 */
    private long perRoundMillis() {
        if (perRoundMillisOverride >= 0) {
            return perRoundMillisOverride;
        }
        return TimeUnit.HOURS.toMillis(1) / perHour();
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

    /**
     * 一轮的结果。{@code attempted} = stepped + failed（让路与已查完都不计）。
     *
     * @param doneEngines    本轮候选为空（该引擎已把在库的书查完）的引擎数
     * @param quotaExhausted 今日额度是否到顶
     * @param coolingDown    上游线路是否在冷却
     * @param lineDown       是否因连续失败收尾
     */
    record RoundStats(int attempted, int stepped, int failed, int yielded, int doneEngines,
                      boolean quotaExhausted, boolean coolingDown, boolean lineDown) {

        /** 有引擎在跑、但一本书都没推进也没失败：全库已查完，可以拉长复查间隔。 */
        boolean idle() {
            return attempted == 0 && yielded == 0 && doneEngines > 0;
        }
    }
}
