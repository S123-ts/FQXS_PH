package org.example.fqxs.service;

import org.example.fqxs.model.SampleSpec;

import java.util.List;
import java.util.Map;

/**
 * AI 文判定编排：免费引擎跑整本初筛，页面上按引擎逐章校验。
 *
 * <p>结论分两层落库：明细表 novel_ai_verdict 每引擎每型号一条（可追溯、可对比），
 * 主表 ai_* 是综合结论（最新免费判定，逐章校验命中时覆盖）。</p>
 *
 * <p>逐章校验规则：</p>
 * <ol>
 *   <li>章节范围与服务端取样条件一致，默认前 10 章；</li>
 *   <li>同一模型同一章节只校验一次，已查过的批次不再重复消耗额度；</li>
 *   <li>命中 AI 不再终止，该模型继续把剩余章节查完，结论逐轮写进详情流水；</li>
 *   <li>每次点击推进一章，型号在引擎的型号池里随机轮换，池内模型全部查完才算完成。</li>
 * </ol>
 */
public interface AiDetectService {

    /**
     * 用指定的免费引擎判定单本书：按取样策略抽章节拼接后送判，结论写明细表与主表综合列。
     *
     * <p>幂等守卫：该引擎对这本书已有初筛结论时直接返回已有结论（cached=true），
     * 不抓章节、不调模型，避免「校验本页」与批量初筛交叉时重复消耗额度；
     * force=true 时忽略已有结论强制重判。</p>
     *
     * @param bookId 书籍 ID
     * @param engine 免费引擎标识（glm-4-flash / qianfan-ernie-speed / qwen2.5-7b / 自定义 id）
     * @param force  true 表示忽略已有结论强制重判
     * @return 本次判定结论
     */
    FreeDetectResult detectFree(String bookId, String engine, boolean force);

    /**
     * 同 {@link #detectFree(String, String, boolean)}，但按指定取样规则抽章节：
     * 前 10 章 / 最新 10 章 / 随机两段各 5 章，null 走默认（前 10 章）。
     * 幂等守卫同样生效——换规则重判需 force=true。
     *
     * @param sample 取样规则
     */
    default FreeDetectResult detectFree(String bookId, String engine, boolean force, SampleSpec sample) {
        return detectFree(bookId, engine, force, sample, false);
    }

    /**
     * 带触发来源的判定：{@code rerun=true} 表示用户在行内点了模型名（徽章）手动重跑，
     * 流水 kind 会记成 {@code rerun}，详情里能与批量初筛区分开。
     *
     * @param sample 取样规则
     * @param rerun  是否手点徽章触发的强制重跑
     */
    FreeDetectResult detectFree(String bookId, String engine, boolean force, SampleSpec sample, boolean rerun);

    /**
     * 免费引擎批量初筛：按热度挑出该引擎尚未判定的书，逐本判定。
     *
     * @param limit  本次最多处理的本数，超过配置上限会被钳住
     * @param engine 免费引擎标识
     * @return 批量结果与该引擎的剩余待判数
     */
    SweepResult sweepFree(int limit, String engine);

    /**
     * 同 {@link #sweepFree(int, String)}，但按指定取样策略抽章节（null 走默认前 10 章）。
     *
     * @param sample 取样策略描述
     */
    SweepResult sweepFree(int limit, String engine, SampleSpec sample);

    /**
     * 逐章校验推进一步：按取样条件取章节，一次判定一章。
     *
     * <p>同一模型同一章节不重复校验：进度按 (book, engine, model#verify) 独立记录，
     * 已查完这批章节的型号会被跳过，自动换型号池里的下一个。</p>
     *
     * @param bookId 书籍 ID
     * @param engine 已配置 Key 的引擎标识
     * @param sample 取样策略，null 走默认（前 10 章）
     * @return 本次校验的章节、结论与是否完成
     */
    VerifyStepResult stepVerify(String bookId, String engine, SampleSpec sample);

    /**
     * 同上，使用默认取样条件（前 10 章）。
     *
     * @param bookId 书籍 ID
     * @param engine 引擎标识
     * @return 本次校验结果
     */
    default VerifyStepResult stepVerify(String bookId, String engine) {
        return stepVerify(bookId, engine, null);
    }

    /** 页面引导用：引擎是否就绪、各引擎还剩多少书待初筛、当前判定分布。 */
    StatusView status();

    /**
     * 当前视图书籍的逐引擎结论（前端渲染徽章用）。
     *
     * @param bookIds 书籍 ID 列表，空列表返回空结果
     * @return 逐引擎结论
     */
    List<EngineVerdict> verdictsForBooks(List<String> bookIds);

    /**
     * 单本书的判定流水（详情面板展开「多次校验」用），新的在前。
     *
     * <p>明细表同一引擎同一型号只留最新一条，重判会覆盖；这张流水表是唯一能回答
     * 「这本书被谁判过几次、每次什么结论」的地方。</p>
     *
     * @param bookId 书籍 ID，空值返回空结果
     * @return 判定流水
     */
    List<VerdictLogEntry> verdictHistory(String bookId);

    /**
     * 单本免费判定结论。
     *
     * @param bookId  书籍 ID
     * @param engine  产出结论的引擎
     * @param aiFlag  判定标记：1=非 AI 2=AI 3=疑似 AI
     * @param aiScore AI 概率（0–100）
     * @param reason  判定依据摘要
     * @param cached  true 表示命中幂等守卫、直接复用了已有结论（本次未调上游）
     */
    record FreeDetectResult(String bookId, String engine, int aiFlag, double aiScore, String reason, boolean cached) {
    }

    /**
     * 逐章校验单步结论。
     *
     * @param bookId         书籍 ID
     * @param engine         引擎标识
     * @param model          本次校验用的型号（型号池内随机）
     * @param chapterIndex   本次校验的章号（从 1 开始）
     * @param totalChapters  本批要校验的章节总数（取样条件决定，默认 10）
     * @param stepIndex      该型号在本批中已完成的章数（含本次）
     * @param chapterTitle   章节标题
     * @param aiScore        本章 AI 风险（0–100）
     * @param aiHit          本章是否判定为 AI
     * @param finished       该型号是否已查完本批章节
     * @param allDone        型号池里已没有任何型号可推进（整批真正结束，前端据此停循环）
     * @param chapterSkipped 本章是否因试读/取文失败被跳过（未产生判定）
     * @param message        给用户看的一句话
     */
    record VerifyStepResult(String bookId, String engine, String model,
                            int chapterIndex, int totalChapters, int stepIndex,
                            String chapterTitle, double aiScore, boolean aiHit,
                            boolean finished, boolean allDone, boolean chapterSkipped, String message) {
    }

    /**
     * 批量初筛结果。
     *
     * @param processed    成功判定的本数
     * @param failed       失败本数（上游风控、额度耗尽等）
     * @param aiCount      本次判为 AI 的本数
     * @param suspectCount 本次判为疑似 AI 的本数
     * @param remaining    该引擎仍未判定的本数
     * @param errors       失败原因（最多保留前 5 条）
     */
    record SweepResult(int processed, int failed, int aiCount, int suspectCount, long remaining, List<String> errors) {
    }

    /**
     * 单引擎单书的展示用结论。
     *
     * @param bookId      书籍 ID
     * @param engine      引擎标识
     * @param model       产出该结论的型号
     * @param promptId    该结论使用的提示词变体，无则为空
     * @param sampleMode  取样档位（free/member + 策略），逐章校验为空
     * @param sampleSpan  实际采用的章号跨度，逐章校验为空
     * @param aiFlag      1=非 AI 2=AI 3=疑似 AI
     * @param aiScore     AI 概率（0–100）
     * @param reason      理由摘要
     * @param checkedTime 判定时刻（yyyy-MM-dd HH:mm:ss）
     */
    record EngineVerdict(String bookId, String engine, String model, String promptId,
                         String sampleMode, String sampleSpan,
                         int aiFlag, double aiScore, String reason,
                         String checkedTime, int chaptersChecked) {
    }

    /**
     * 一轮判定流水。
     *
     * @param bookId          书籍 ID
     * @param engine          引擎标识
     * @param model           该轮实际型号
     * @param promptId        该轮提示词变体
     * @param sampleMode      该轮取样档位（逐章校验轮为空）
     * @param sampleSpan      该轮章号跨度
     * @param aiFlag          该轮标记：1=非 AI 2=AI 3=疑似 AI 0=仅进度
     * @param aiScore         该轮 AI 概率（0–100）
     * @param reason          该轮理由摘要
     * @param checkedTime     该轮时刻（yyyy-MM-dd HH:mm:ss）
     * @param chaptersChecked 该轮推进到的章数
     * @param kind            动作：sweep=整本初筛 / verify=逐章校验一步 / progress=仅推进进度
     */
    record VerdictLogEntry(String bookId, String engine, String model, String promptId,
                           String sampleMode, String sampleSpan, int sampleChapters,
                           int aiFlag, Double aiScore, String reason,
                           String checkedTime, int chaptersChecked, String kind) {
    }

    /**
     * 判定能力与判定分布快照。
     *
     * @param enabled          判定功能是否开启
     * @param availableEngines 可用的免费引擎（Key 已配置的），顺序即选择框顺序
     * @param pendingByEngine  各免费引擎尚未判定的书籍数
     * @param aiCount          综合结论为 AI 的书籍数
     * @param suspectCount     综合结论为疑似 AI 的书籍数
     * @param verifyEngines    可逐章校验的引擎（含短名），前端按它渲染按钮
     * @param verifyMaxChapters 单批逐章校验的章节数（默认前 10 章）
     * @param modelPools       各引擎的型号池（逐章校验按池随机轮换）
     * @param bannableEngines  允许被自动熔断切换的引擎
     */
    record StatusView(boolean enabled, List<String> availableEngines,
                      Map<String, Long> pendingByEngine, long aiCount, long suspectCount,
                      List<Map<String, String>> verifyEngines, int verifyMaxChapters,
                      Map<String, List<String>> modelPools, List<String> bannableEngines) {
    }
}
