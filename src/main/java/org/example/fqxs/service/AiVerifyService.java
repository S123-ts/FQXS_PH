package org.example.fqxs.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.client.AiDetectClient;
import org.example.fqxs.client.ThirdPartyChapterClient;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.entity.Novel;
import org.example.fqxs.entity.NovelAiVerdict;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.exception.OperationConflictException;
import org.example.fqxs.model.AiVerdict;
import org.example.fqxs.model.ChapterSample;
import org.example.fqxs.model.SampleSpec;
import org.example.fqxs.repository.NovelAiVerdictRepository;
import org.example.fqxs.repository.NovelRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 逐章校验：一步推进一章，由调用方循环到整批查完。
 *
 * <p><b>为什么服务端只做一步、不自己循环</b>：一批 10 章的模型调用要 30–60 秒，
 * 塞进一个请求里界面全程没有进度、断网则整批作废；拆成逐章请求后前端能实时显示
 * {@code n/总数}，而进度游标在库里（{@code model#verify} 行），中途关页面再点一次
 * 会从断点继续，已完成章节不会重复消耗额度。前端 {@code runVerifyBatch} 即该循环。</p>
 *
 * <p>校验语义（原为「命中即停 + 按钮锁死」）：</p>
 * <ul>
 *   <li>章节范围与取样条件一致，默认前 10 章，由 {@link AiSampleCollector#plan} 计算；</li>
 *   <li>进度与结论写在型号加 {@code #verify} 后缀的独立行，按 (book, engine, model) 隔离，
 *       所以<b>同一模型同一章节不会重复校验</b>，已查完这批章节的型号直接跳过；</li>
 *   <li>命中 AI 只记录该章结论，不再终止——继续把剩余章节查完，避免"只查了一次"
 *       就给出片面结论；流水表逐轮记录，详情面板能看到每一章；</li>
 *   <li>型号在引擎的型号池里随机轮换，池内所有型号都查完才算真正完成（{@code allDone}）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiVerifyService {

    /** 逐章校验的型号后缀：让进度/结论与整本初筛分开存行。 */
    public static final String VERIFY_MODEL_SUFFIX = "#verify";

    /**
     * 正在逐章校验的「书 + 引擎」。
     *
     * <p>游标读与写之间隔着一次 30–60 秒的模型调用，两个标签页同时点会判同一章、
     * 白耗一次额度（写库不冲突，因为都写同一个 nextChecked）。前端去重键只在
     * 本标签页内有效，这里按进程内占位把重复那份挡在入口。</p>
     */
    private final Set<String> inFlightVerifies = ConcurrentHashMap.newKeySet();

    private final AiDetectClient aiDetectClient;
    private final ThirdPartyChapterClient thirdPartyChapterClient;
    private final ChapterContentService chapterContentService;
    private final AiSampleCollector sampleCollector;
    private final AiDetectConfig aiDetectConfig;
    private final AiEngineRegistry engineRegistry;
    private final AiEngineRouter engineRouter;
    private final NovelRepository novelRepository;
    private final NovelAiVerdictRepository verdictRepository;
    private final AiVerdictWriter verdictWriter;
    private final AiFaultGuard faultGuard;

    /**
     * 逐章校验推进一步（全型号池）。
     *
     * @param bookId 书籍 ID
     * @param engine 已配置 Key 的引擎标识
     * @param sample 取样策略，null 走默认（前 10 章）
     * @return 本次校验的章节、结论与是否完成
     */
    public AiDetectService.VerifyStepResult stepVerify(String bookId, String engine, SampleSpec sample) {
        return stepVerify(bookId, engine, sample, null);
    }

    /**
     * 逐章校验推进一步，可限定型号范围。
     *
     * @param allowedModels 只在池内这些型号上推进；null 或空表示全池（页面手动校验的行为）。
     *                      后台逐章校验传<b>家族代表型号</b>（{@link AiEngineRegistry#distinctModelsOf}），
     *                      否则同一模型的上下文变体会把免费额度各烧一遍
     * @return 本次校验的章节、结论与是否完成
     */
    public AiDetectService.VerifyStepResult stepVerify(String bookId, String engine, SampleSpec sample,
                                                       List<String> allowedModels) {
        ensureEnabled();
        if (bookId == null || bookId.isBlank()) {
            throw new IllegalArgumentException("bookId 不能为空");
        }
        if (AiEngineRouter.isRandom(engine)) {
            throw new IllegalArgumentException("逐章校验需要指定具体引擎，不能使用 random");
        }
        String flightKey = bookId + "|" + engine;
        if (!inFlightVerifies.add(flightKey)) {
            throw new OperationConflictException("这本书的这个引擎正在另一处逐章校验，等它结束再点");
        }
        try {
            return doStepVerify(bookId, engine, sample, allowedModels);
        } finally {
            inFlightVerifies.remove(flightKey);
        }
    }

    private AiDetectService.VerifyStepResult doStepVerify(String bookId, String engine, SampleSpec sample,
                                                          List<String> allowedModels) {
        // 可用性判定统一走 router：启用 + 未暂停 + OpenAI 兼容 + 已配 Key。
        // 原先这里手写了一遍（且漏了兼容检查），非兼容引擎要等远程调用前才被拒
        String actualEngine = engineRouter.resolveAvoiding(engine, List.of());
        // 先确认这本书在库，再打上游：省掉一次必然无效的章节列表请求
        List<Novel> novelRows = novelRepository.findAllByBookId(bookId);
        if (novelRows.isEmpty()) {
            throw new IllegalArgumentException("未找到 bookId: " + bookId);
        }

        // 章节范围与取样条件完全一致：默认前 10 章，可传 head/mid/recent/random
        SampleSpec effective = sample == null ? SampleSpec.defaultSpec() : sample;
        AiSampleCollector.ChapterPlan plan = sampleCollector.plan(bookId, effective);
        if (plan.isEmpty()) {
            throw new IllegalArgumentException("该书没有可校验的章节: bookId=" + bookId);
        }
        int total = plan.indices().size();
        // 游标必须绑定取样策略：换了策略还沿用旧进度，会跳过前 N 章或直接误报“已查完”。
        // 与初筛的 sample_mode 同格式（数据源前缀:档位），历史行有值即可直接比对
        String sampleKey = (effective.usesThirdParty() ? "third:" : "official:") + effective.modeName();

        // 调用方限定了型号范围（后台按家族代表推进）就只在子集里挑，否则用全池
        List<String> models = new ArrayList<>(allowedModels == null || allowedModels.isEmpty()
                ? engineRegistry.modelsOf(actualEngine)
                : allowedModels);
        // 型号随机：每次点击随机起点，让额度摊到池内各型号，而不是永远只用第一个
        Collections.shuffle(models, ThreadLocalRandom.current());
        for (String model : models) {
            String rowModel = model + VERIFY_MODEL_SUFFIX;
            NovelAiVerdict existing = verdictRepository
                    .findByBookIdAndEngineAndModel(bookId, actualEngine, rowModel).orElse(null);
            // 旧行没有策略标记（null）也算不一致：无法确认它属于哪批章节，按 0 重来
            int checked = existing != null && sampleKey.equals(existing.getSampleMode())
                    ? existing.getChaptersChecked() : 0;
            if (checked >= total) {
                // 同一模型同一批章节已查完：不重复校验，换型号池里的下一个
                continue;
            }
            return verifyOne(bookId, actualEngine, model, rowModel, plan, checked, existing, sampleKey);
        }

        log.info("逐章校验已完成: bookId={}, engine={}, {} 个型号均已校验完前 {} 章",
                bookId, actualEngine, models.size(), total);
        return new AiDetectService.VerifyStepResult(bookId, actualEngine, "", 0, total, total,
                "", 0, false, true, true, false,
                "本次校验的 " + models.size() + " 个型号都已查完这 " + total + " 章，不再重复消耗额度");
    }

    /**
     * 推进指定型号的一章。
     *
     * @param checked  该型号已完成的章数（游标）
     * @param existing 该型号的进度行，可为 null
     */
    private AiDetectService.VerifyStepResult verifyOne(String bookId, String engine, String model,
                                                      String rowModel, AiSampleCollector.ChapterPlan plan,
                                                      int checked, NovelAiVerdict existing, String sampleKey) {
        int total = plan.indices().size();
        int idx = plan.indices().get(checked);
        // 0-based 章号 → 1-based 章号：日志与详情统一按"第 N 章"展示
        int chapterNo = idx + 1;
        int nextChecked = checked + 1;
        boolean finished = nextChecked >= total;
        // 数据源由取样规则整批决定：官方档（含默认前 10 章）还是第三方档
        boolean thirdParty = plan.thirdParty();

        int previousFlag = existing == null ? 0 : existing.getAiFlag();
        // 命中过即保持命中：后续章节未命中不能撤销"这本书判过 AI"的结论
        boolean keptHit = previousFlag == 2;
        double previousScore = existing == null || existing.getAiScore() == null ? 0 : existing.getAiScore();
        String previousReason = existing == null || existing.getReason() == null ? "" : existing.getReason();

        ChapterSample chapter;
        try {
            chapter = fetchChapter(bookId, plan.itemIds().get(idx), idx, thirdParty);
        } catch (CrawlException failure) {
            /* 取文失败与「试读章」必须分开：前者是线路抖动，这一章没有任何证据，
               游标一旦前进就再也查不回来（stepVerify 会认为这批已查完）。
               只留失败流水、不动游标，下次点击仍从这一章重试。 */
            verdictWriter.writeFailed(bookId, engine, rowModel, failure.getMessage(), sampleKey, null, chapterNo);
            throw failure;
        }
        String text = chapter.text() == null ? "" : chapter.text();

        // 真实试读/VIP 章节：抓到了正文但太短，判定没有意义。推进进度并留一条流水，
        // 否则同一章会被反复请求、永远停在原地（取文失败已在上面单独拦下，不进这里）
        if (text.length() < aiDetectConfig.getMinChapterChars()) {
            int flag = keptHit ? 2 : (finished ? 1 : 0);
            verdictWriter.writeProgress(bookId, engine, rowModel, flag, previousScore, previousReason,
                    nextChecked, chapterNo, existing == null ? null : existing.getPromptId(), sampleKey);
            String message = "第 " + chapterNo + " 章正文仅 " + text.length() + " 字（试读/VIP），已跳过判定"
                    + (finished ? "，该模型已查完这 " + total + " 章" : "，可继续下一章");
            log.info("逐章校验跳过: bookId={}, engine={}, model={}, 第 {} 章仅 {} 字",
                    bookId, engine, model, chapterNo, text.length());
            return new AiDetectService.VerifyStepResult(bookId, engine, model, chapterNo, total, nextChecked,
                    chapter.title(), previousScore, false, finished, false, true, message);
        }

        int maxChars = aiDetectConfig.getMaxCharsPerBook();
        String sampleText = text.length() > maxChars ? text.substring(0, maxChars) : text;

        // 远程调用不占事务。失败留两处证：引擎级故障表（熔断计数与停用依据）与判定流水
        // （时间线上「这一章试过但没出结论」）；只写故障表会让详情面板出现空档
        AiVerdict verdict;
        try {
            verdict = aiDetectClient.detectFree(engine, model, sampleText);
        } catch (CrawlException failure) {
            faultGuard.recordFailure(engine, model, failure, bookId);
            verdictWriter.writeFailed(bookId, engine, rowModel, failure.getMessage(), sampleKey, null, chapterNo);
            throw failure;
        }
        // 与初筛同一条口径：判定成功即清零连续失败计数。
        // 只在失败侧加计数而不在成功侧清零，会让零星失败累积到阈值、把引擎误停用
        faultGuard.recordSuccess(engine);
        double score = roundScore(verdict.aiProbability());
        boolean aiHit = score >= aiDetectConfig.getFreeAiThreshold();
        // 明细口径：命中后保持 2，后续干净章不能撤销「这本书判过 AI」的结论
        int flag = (aiHit || keptHit) ? 2 : (finished ? 1 : 0);
        double rowScore = keptHit && !aiHit ? previousScore : score;
        String rowReason = keptHit && !aiHit ? previousReason : verdict.reason();
        /* 流水口径：这一章实际判成什么。命中过也不能把本章的 10% 写成首次命中的 95%——
           详情面板逐章展示、加权平均按章计权，都依赖这份真实分数；
           两处口径混用会让时间线出现「10 章全 95%」的假象 */
        int roundFlag = aiHit ? 2 : (finished ? 1 : 0);

        // 每一轮都写明细 + 流水：详情面板的「校验记录」就是靠流水逐章展示
        verdictWriter.writeVerify(bookId, engine, rowModel,
                new AiVerdictWriter.RoundVerdict(flag, rowScore, rowReason),
                new AiVerdictWriter.RoundVerdict(roundFlag, score, verdict.reason()),
                nextChecked, chapterNo, verdict.promptId(), aiHit, sampleKey);

        String message = aiHit
                ? "第 " + chapterNo + " 章命中 AI（风险 " + score + "%），已记入详情"
                    + (finished ? "，该模型已查完这 " + total + " 章" : "，可继续校验下一章")
                : "第 " + chapterNo + " 章非 AI（风险 " + score + "%）"
                    + (finished ? "，该模型已查完这 " + total + " 章" : "，可继续下一章");
        log.info("逐章校验一步: bookId={}, engine={}, model={}, 第 {} 章, 进度 {}/{}, 风险={}, 命中={}, 完成={}",
                bookId, engine, model, chapterNo, nextChecked, total, score, aiHit, finished);
        return new AiDetectService.VerifyStepResult(bookId, engine, model, chapterNo, total, nextChecked,
                chapter.title(), rowScore, aiHit, finished, false, false, message);
    }

    /**
     * 抓一章正文：官方档走 {@link ChapterContentService}（库优先，前 10 章判第二次就不再打上游），
     * 第三方档直接走会员通道。
     *
     * <p>取文失败原样抛出，由 {@link #verifyOne} 记失败流水并停在原地——
     * 共享线路的抖动不能伪装成「试读跳过」，否则这批章节会被永久跳过。</p>
     */
    private ChapterSample fetchChapter(String bookId, String itemId, int chapterIndex, boolean thirdParty) {
        if (!thirdParty) {
            return chapterContentService.getOrFetch(bookId, itemId, chapterIndex,
                    sampleCollector::awaitUpstreamSlot);
        }
        sampleCollector.awaitUpstreamSlot();
        return thirdPartyChapterClient.fetchChapter(itemId);
    }

    private double roundScore(double score) {
        return Math.round(score * 100.0) / 100.0;
    }

    private void ensureEnabled() {
        if (!aiDetectConfig.isEnabled()) {
            throw new IllegalStateException("AI 判定已关闭（ai.detect.enabled=false）");
        }
    }
}
