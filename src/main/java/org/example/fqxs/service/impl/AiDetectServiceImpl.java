package org.example.fqxs.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.client.AiDetectClient;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.entity.AiEngine;
import org.example.fqxs.entity.NovelAiVerdict;
import org.example.fqxs.entity.NovelAiVerdictLog;
import org.example.fqxs.exception.CrawlException;
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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 免费判定编排：单本判定与批量初筛。
 *
 * <p>2026-10-22 拆分后只保留编排职责：取样交给 {@link AiSampleCollector}、
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
        ensureEnabled();
        // engine 为空或 random 时随机挑一个可用引擎，并避开这本书已判过的引擎
        String actualEngine = engineRouter.resolveAvoiding(engine, judgedEngines(bookId));
        requireFreeEngine(actualEngine);
        List<String> models = engineRegistry.modelsOf(actualEngine);
        // 每次判定在勾选的型号池里随机取一个：多模型分摊额度与限流
        String pickedModel = engineRegistry.pickModel(actualEngine);

        // 0. 幂等守卫：该引擎的**任一勾选型号**已有初筛结论就复用（同一本书不重复烧额度）；
        //    切型号池后新池里的型号都没有结论，会真正重判。
        if (!force) {
            NovelAiVerdict existing = verdictRepository
                    .findByBookIdAndEngineAndModelIn(bookId, actualEngine, models).stream()
                    .filter(this::isInitialSweepVerdict)
                    .findFirst()
                    .orElse(null);
            if (existing != null) {
                double score = existing.getAiScore() == null ? 0 : existing.getAiScore();
                String reason = existing.getReason() == null ? "" : existing.getReason();
                log.info("免费初筛命中缓存: bookId={}, engine={}, model={}, 标记={}",
                        bookId, actualEngine, existing.getModel(), existing.getAiFlag());
                return new FreeDetectResult(bookId, actualEngine, existing.getAiFlag(), score, reason, true);
            }
        }

        // 1. 取样：按策略抽章节拼接，VIP 试读会在取样器里被跳过
        AiSampleCollector.CollectedSample sampleResult = sampleCollector.collect(bookId, sample);
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
                rerun ? NovelAiVerdictLog.KIND_RERUN : NovelAiVerdictLog.KIND_SWEEP);

        log.info("免费初筛完成: bookId={}, engine={}, 型号={}, 标记={}, 概率={}, 取样 {} 字/{}, 触发={}, 换过型号={}",
                bookId, actualEngine, usedModel, aiFlag, roundedScore, sampleText.length(),
                sampleResult.mode(), rerun ? "手点重跑" : "初筛",
                usedModel.equals(pickedModel) ? "否" : "是(原 " + pickedModel + ")");
        return new FreeDetectResult(bookId, verdict.engine(), aiFlag, roundedScore, verdict.reason(), false);
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
        return row != null && row.getAiFlag() != null && row.getAiFlag() > 0
                && (row.getChaptersChecked() == null || row.getChaptersChecked() == 0);
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
        requireFreeEngine(engine);

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
        }
    }

    @Override
    public VerifyStepResult stepVerify(String bookId, String engine, SampleSpec sample) {
        return verifyService.stepVerify(bookId, engine, sample);
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
        return verdictRepository.findByBookIdIn(List.of(bookId)).stream()
                .map(NovelAiVerdict::getEngine)
                .distinct()
                .toList();
    }

    /** 免费通道的引擎校验：停用、非 OpenAI 兼容、未配 Key 都拒绝。 */
    private void requireFreeEngine(String engine) {
        AiEngine record = engineRegistry.find(engine)
                .orElseThrow(() -> new IllegalArgumentException("不支持的免费引擎: " + engine));
        if (!record.isOpenAiCompatible()) {
            throw new IllegalArgumentException("引擎 " + engine + " 不是 OpenAI 兼容通道，不能用于初筛");
        }
        if (!record.isEnabled()) {
            throw new IllegalArgumentException("引擎 " + engine + " 已停用，请在「API 密钥」面板启用");
        }
        if (record.isSuspended()) {
            // 自动停用是"连续失败太多"的结果，不该被一次手动点击绕开
            throw new IllegalArgumentException("引擎 " + engine + " 已自动停用至 " + record.getDisableUntil()
                    + "，原因：" + record.getDisableReason());
        }
        if (!engineRegistry.hasKey(engine)) {
            throw new IllegalArgumentException("引擎 " + engine + " 未配置 API Key，请在「API 密钥」面板配置");
        }
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
