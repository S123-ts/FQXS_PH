package org.example.fqxs.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.client.AiDetectClient;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.entity.AiEngine;
import org.example.fqxs.entity.NovelAiVerdict;
import org.example.fqxs.entity.NovelAiVerdictLog;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.logging.BookLogContext;
import org.example.fqxs.model.AiVerdict;
import org.example.fqxs.model.SampleSpec;
import org.example.fqxs.repository.NovelAiVerdictRepository;
import org.example.fqxs.repository.NovelRepository;
import org.example.fqxs.service.AiDetectService;
import org.example.fqxs.service.AiDetectStatusService;
import org.example.fqxs.service.AiEngineRegistry;
import org.example.fqxs.service.AiEngineRouter;
import org.example.fqxs.service.AiFaultGuard;
import org.example.fqxs.service.AiSampleCollector;
import org.example.fqxs.service.AiVerdictWriter;
import org.example.fqxs.service.AiVerifyService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 免费判定编排：单本判定与批量初筛。
 *
 * <p>只保留编排职责：取样交给 {@link AiSampleCollector}、
 * 落库交给 {@link AiVerdictWriter}、逐章校验交给 {@link AiVerifyService}、
 * 状态查询交给 {@link AiDetectStatusService}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiDetectServiceImpl implements AiDetectService {

    /** 批量结果里最多带回几条错误样本。 */
    private static final int MAX_ERROR_SAMPLES = 5;

    /** 单条错误消息的截断长度。 */
    private static final int MAX_ERROR_LENGTH = 80;

    /** 正在判定中的书：两个标签页同时点初筛时跳过重复那份，避免同一本重复消耗额度。 */
    private final Set<String> inFlightSweeps = ConcurrentHashMap.newKeySet();

    private final NovelRepository novelRepository;
    private final NovelAiVerdictRepository verdictRepository;
    private final AiDetectClient aiDetectClient;
    private final AiDetectConfig aiDetectConfig;
    private final AiEngineRegistry engineRegistry;
    private final AiEngineRouter engineRouter;
    private final AiSampleCollector sampleCollector;
    private final AiVerdictWriter verdictWriter;
    private final AiVerifyService verifyService;
    private final AiDetectStatusService statusService;
    private final AiFaultGuard faultGuard;

    @Override
    public FreeDetectResult detectFree(String bookId, String engine, boolean force) {
        return detectFree(bookId, engine, force, null);
    }

    @Override
    public FreeDetectResult detectFree(String bookId, String engine, boolean force, SampleSpec sample) {
        return detectFree(bookId, engine, force, sample, false);
    }

    @Override
    public FreeDetectResult detectFree(String bookId, String engine, boolean force, SampleSpec sample,
                                       boolean rerun) {
        // 圈定这本书：取样、模型调用、落库期间的日志都归到 bookId 名下（app_log.book_id）
        try (BookLogContext ignored = BookLogContext.open(bookId)) {
            return detectFreeInternal(bookId, engine, force, sample, rerun);
        }
    }

    private FreeDetectResult detectFreeInternal(String bookId, String engine, boolean force,
                                                SampleSpec sample, boolean rerun) {
        ensureEnabled();
        // engine 为空或 random 时随机挑一个可用引擎，并避开这本书已判过的引擎。
        // 可用性判定（启用/未暂停/兼容/有 Key）只由 router 负责，这里不再重复校验；
        // 手点重跑走 rerun 专用路由：熔断只是引擎级粗粒度，型号池内还有活型号就不该被挡
        String actualEngine = rerun
                ? engineRouter.resolveForRerun(engine)
                : engineRouter.resolveAvoiding(engine, judgedEngines(bookId));
        List<String> models = engineRegistry.modelsOf(actualEngine);
        // 该书在此引擎下的已有明细行：型号避让与幂等守卫共用一次查询
        List<NovelAiVerdict> existingRows = verdictRepository
                .findByBookIdAndEngineAndModelIn(bookId, actualEngine, models);
        // 优先在没判过的型号里随机，全判过再全池随机：多型号分摊额度，重跑不总撞同一个
        String pickedModel = pickFreshModel(models, existingRows);

        // 0. 幂等守卫：该引擎的任一勾选型号已有初筛结论就复用（同一本书不重复消耗额度）；
        //    切型号池后新池里的型号都没有结论，会真正重判。
        if (!force) {
            NovelAiVerdict existing = existingRows.stream()
                    .filter(this::isInitialSweepVerdict)
                    // 多型号都判过时取最新一条：仓储没有排序，不能把 findFirst 的偶然顺序当结论
                    .max(Comparator.comparing(NovelAiVerdict::getCheckedTime,
                                    Comparator.nullsFirst(Comparator.naturalOrder()))
                            .thenComparing(NovelAiVerdict::getId))
                    .orElse(null);
            if (existing != null) {
                double score = existing.getAiScore() == null ? 0 : existing.getAiScore();
                String reason = existing.getReason() == null ? "" : existing.getReason();
                log.info("免费初筛命中缓存: bookId={}, engine={}, model={}, 标记={}",
                        bookId, actualEngine, existing.getModel(), existing.getAiFlag());
                return new FreeDetectResult(bookId, actualEngine, existing.getAiFlag(), score, reason, true);
            }
        }

        // 1. 取样：按策略抽章节拼接，VIP 试读会在取样器里被跳过。
        //    取样失败也算一次审核尝试：没有结论，但时间线上要留下"这轮试过"
        AiSampleCollector.CollectedSample sampleResult;
        try {
            sampleResult = sampleCollector.collect(bookId, sample);
        } catch (RuntimeException sampleFailure) {
            verdictWriter.writeFailed(bookId, actualEngine, pickedModel, sampleFailure.getMessage(), null, null, 0);
            throw sampleFailure;
        }
        String sampleText = sampleResult.text();
        // 2. 判定：远程调用放在事务外。型号级容错——掷中的这个不行就换池里下一个再试，
        //    每次失败都留下事实（原因 + 上游返回片段）；连续失败到阈值由 AiFaultGuard 停用引擎。
        List<String> attemptOrder = modelAttemptOrder(models, pickedModel);
        AiVerdict verdict = null;
        CrawlException lastFailure = null;
        String usedModel = pickedModel;
        for (String model : attemptOrder) {
            usedModel = model;
            try {
                verdict = aiDetectClient.detectFree(actualEngine, model, sampleText);
                break;
            } catch (CrawlException failure) {
                faultGuard.recordFailure(actualEngine, model, failure, bookId);
                // 每个失败型号各留一条流水：型号池 4 个全挂时，时间线要能看出试过哪几个
                verdictWriter.writeFailed(bookId, actualEngine, model, failure.getMessage(),
                        sampleResult.mode(), sampleResult.span(), 0);
                lastFailure = failure;
                log.warn("型号判定失败: engine={}, model={}, 原因={}, 尝试 {}/{}",
                        actualEngine, model, failure.getMessage(), attemptOrder.indexOf(model) + 1,
                        attemptOrder.size());
            }
        }
        if (verdict == null) {
            // 整个型号池都不行：把最后一次失败原样抛给上层（页面熔断据此换引擎）
            throw lastFailure;
        }
        faultGuard.recordSuccess(actualEngine);
        // 3. 概率映射标记并落库：明细与主表同事务（写入器内部处理并发冲突）
        int aiFlag = resolveFreeFlag(verdict.aiProbability());
        double roundedScore = roundScore(verdict.aiProbability());
        verdictWriter.writeSweep(bookId, verdict.engine(), usedModel, aiFlag, roundedScore,
                verdict.reason(), verdict.promptId(), sampleResult.mode(), sampleResult.span(),
                // 手点徽章重跑单独记 rerun：详情里能看出这轮是人手动发起的，不是批量初筛
                rerun ? NovelAiVerdictLog.KIND_RERUN : NovelAiVerdictLog.KIND_SWEEP,
                // 实际采用章数：加权平均的权重（span 会被截断，不能反解）
                sampleResult.chapters());

        log.info("免费初筛完成: bookId={}, engine={}, 型号={}, 标记={}, 概率={}, 取样 {} 字/{}, 触发={}, 换过型号={}",
                bookId, actualEngine, usedModel, aiFlag, roundedScore, sampleText.length(),
                sampleResult.mode(), rerun ? "手点重跑" : "初筛",
                usedModel.equals(pickedModel) ? "否" : "是(原 " + pickedModel + ")");
        return new FreeDetectResult(bookId, verdict.engine(), aiFlag, roundedScore, verdict.reason(), false);
    }

    /**
     * 型号挑选：优先从这本书还没判过的型号里随机，全部判过再退回全池随机。
     *
     * <p>失败只写流水不动明细（见 {@code AiVerdictWriter#writeFailed}），所以「判过」
     * 以明细表为准——429 失败的型号下次仍可能被选中重试，与熔断的证据链互不干扰。</p>
     */
    private static String pickFreshModel(List<String> models, List<NovelAiVerdict> existingRows) {
        Set<String> judged = existingRows.stream()
                .map(NovelAiVerdict::getModel)
                .collect(java.util.stream.Collectors.toSet());
        List<String> fresh = models.stream().filter(model -> !judged.contains(model)).toList();
        List<String> pool = fresh.isEmpty() ? models : fresh;
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    /**
     * 判定尝试顺序：先掷到的型号，失败后按池内顺序补上其余型号。
     * 池里只有一个型号时不会重复试第二次（同一型号同参数的重试已在 client 里做过）。
     */
    private static List<String> modelAttemptOrder(List<String> models, String picked) {
        List<String> order = new ArrayList<>();
        order.add(picked);
        for (String model : models) {
            if (!model.equals(picked)) {
                order.add(model);
            }
        }
        return order;
    }

    /**
     * 明细行是否是一次完整的初筛结论：区别于逐章校验的进度行——那类行 chaptersChecked 恒大于 0。
     *
     * @param row 明细行
     * @return true 表示可直接复用为初筛结论
     */
    private boolean isInitialSweepVerdict(NovelAiVerdict row) {
        return row != null && row.getAiFlag() > 0 && row.getChaptersChecked() == 0;
    }

    @Override
    public SweepResult sweepFree(int limit, String engine) {
        return sweepFree(limit, engine, null);
    }

    @Override
    public SweepResult sweepFree(int limit, String engine, SampleSpec sample) {
        ensureEnabled();
        if (AiEngineRouter.isRandom(engine)) {
            throw new IllegalArgumentException("批量初筛需要指定具体引擎（random 只用于单本判定与校验本页）");
        }
        // 与单本判定共用同一套可用性判定：显式引擎在 router 里同样会被完整校验
        engineRouter.resolveAvoiding(engine, List.of());

        int requestedLimit = limit <= 0 ? aiDetectConfig.getAutoSweepLimit() : limit;
        int safeLimit = Math.max(1, Math.min(requestedLimit, aiDetectConfig.getSweepLimitMax()));
        List<String> models = engineRegistry.modelsOf(engine);
        long pending = novelRepository.countBookIdsMissingEngineVerdict(engine, models);
        if (pending <= 0) {
            return new SweepResult(0, 0, 0, 0, 0, List.of());
        }
        /* 在待判集里随机挑一页，而不是固定从第 0 页取：
           固定取第 0 页 = 永远先判热度最高的那几本，排在后面的书永远轮不到；
           而且其中某本一直失败（429/风控）时没有结论落库，下次还排第一，
           于是每次点初筛都卡在同一批书上。随机页让失败的书散开，覆盖也能摊到全库。 */
        int pages = (int) Math.min(Integer.MAX_VALUE, (pending + safeLimit - 1) / safeLimit);
        int page = pages > 1 ? ThreadLocalRandom.current().nextInt(pages) : 0;
        List<String> bookIds = novelRepository.findBookIdsMissingEngineVerdict(engine, models,
                PageRequest.of(page, safeLimit));
        if (bookIds.isEmpty() && page != 0) {
            // count 与分页查询之间另一标签页刚判完，页码越界：退回第一页而不是空跑
            bookIds = novelRepository.findBookIdsMissingEngineVerdict(engine, models, PageRequest.of(0, safeLimit));
        }
        if (bookIds.isEmpty()) {
            return new SweepResult(0, 0, 0, 0, pending, List.of());
        }

        AtomicInteger processed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger aiCount = new AtomicInteger();
        AtomicInteger suspectCount = new AtomicInteger();
        List<String> errors = new ArrayList<>();

        int concurrency = Math.max(1, Math.min(aiDetectConfig.getSweepConcurrency(), bookIds.size()));
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (String bookId : bookIds) {
                tasks.add(() -> {
                    judgeOne(bookId, engine, sample, processed, failed, aiCount, suspectCount, errors);
                    return null;
                });
            }
            // 阻塞到全部完成；被中断时 invokeAll 会取消剩余任务
            pool.invokeAll(tasks);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            log.warn("免费批量初筛被中断，提前结束本次批量");
        } finally {
            pool.shutdownNow();
        }

        long remaining = novelRepository.countBookIdsMissingEngineVerdict(engine, models);
        log.info("免费批量初筛结束: engine={}, 目标 {} 本, 成功 {}, 失败 {}, AI {}, 疑似 {}, 剩余 {}, 并发 {}",
                engine, bookIds.size(), processed.get(), failed.get(), aiCount.get(), suspectCount.get(),
                remaining, concurrency);
        return new SweepResult(processed.get(), failed.get(), aiCount.get(), suspectCount.get(), remaining,
                List.copyOf(errors));
    }

    /** 批量里判定单本：结果计入并发计数器，失败隔离进错误样本列表。 */
    private void judgeOne(String bookId, String engine, SampleSpec sample, AtomicInteger processed,
                          AtomicInteger failed, AtomicInteger aiCount, AtomicInteger suspectCount,
                          List<String> errors) {
        // 幂等守卫是"判之前读库"，两个并发初筛会同时通过；这里用进程内占位把重复那份挡掉
        if (!inFlightSweeps.add(bookId)) {
            log.info("跳过重复初筛: bookId={} 正在另一批判定中", bookId);
            return;
        }
        try {
            FreeDetectResult result = detectFree(bookId, engine, false, sample);
            processed.incrementAndGet();
            if (result.aiFlag() == 2) {
                aiCount.incrementAndGet();
            } else if (result.aiFlag() == 3) {
                suspectCount.incrementAndGet();
            }
        } catch (RuntimeException runtimeException) {
            // 单本失败（风控、额度、上游异常）不能拖垮整批：记下来继续下一本
            failed.incrementAndGet();
            String message = shortMessage(bookId + ": " + runtimeException.getMessage());
            synchronized (errors) {
                if (errors.size() < MAX_ERROR_SAMPLES) {
                    errors.add(message);
                }
            }
            log.warn("免费初筛失败: bookId={}, engine={}, 原因={}", bookId, engine,
                    runtimeException.getMessage());
        } finally {
            inFlightSweeps.remove(bookId);
        }
    }

    @Override
    public VerifyStepResult stepVerify(String bookId, String engine, SampleSpec sample) {
        // 逐章校验的日志也归到这本书名下
        try (BookLogContext ignored = BookLogContext.open(bookId)) {
            return verifyService.stepVerify(bookId, engine, sample);
        }
    }

    @Override
    public StatusView status() {
        return statusService.status();
    }

    @Override
    public List<EngineVerdict> verdictsForBooks(List<String> bookIds) {
        return statusService.verdictsForBooks(bookIds);
    }

    @Override
    public List<VerdictLogEntry> verdictHistory(String bookId) {
        return statusService.verdictHistory(bookId);
    }

    /** 某本书已经判过的引擎（随机路由据此避开重复消耗）。 */
    private List<String> judgedEngines(String bookId) {
        return verdictRepository.findByBookIdInOrderByCheckedTimeDescIdDesc(List.of(bookId)).stream()
                .map(NovelAiVerdict::getEngine)
                .distinct()
                .toList();
    }

    /** 概率保留两位小数：库字段是 DECIMAL(5,2)，多余精度存进去会被静默四舍五入。 */
    private double roundScore(double score) {
        return Math.round(score * 100.0) / 100.0;
    }

    /** 截断失败原因，避免把整段堆栈塞进响应。 */
    private String shortMessage(String message) {
        if (message == null) {
            return "";
        }
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }

    /**
     * 概率映射为标记：高概率 AI、中间区间算疑似、低概率算非 AI。
     *
     * @param aiProbability 0–100 概率
     * @return 1/2/3
     */
    private int resolveFreeFlag(double aiProbability) {
        if (aiProbability >= aiDetectConfig.getFreeAiThreshold()) {
            return 2;
        }
        if (aiProbability >= aiDetectConfig.getFreeSuspectThreshold()) {
            return 3;
        }
        return 1;
    }

    private void ensureEnabled() {
        if (!aiDetectConfig.isEnabled()) {
            throw new IllegalStateException("AI 判定已关闭（ai.detect.enabled=false）");
        }
    }
}
