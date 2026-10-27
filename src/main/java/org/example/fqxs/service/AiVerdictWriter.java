package org.example.fqxs.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.entity.NovelAiVerdict;
import org.example.fqxs.entity.NovelAiVerdictLog;
import org.example.fqxs.repository.NovelAiVerdictLogRepository;
import org.example.fqxs.repository.NovelAiVerdictRepository;
import org.example.fqxs.util.DateUtil;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 判定结论落库：明细 upsert 与主表综合列"同进同退"，并处理并发撞唯一键。
 *
 * <p>从 AiDetectServiceImpl 拆出：编排层只管"判出什么"，这里只管"怎么一致地写进去"。
 * 明细唯一键是 {@code (book_id, engine, model)}，所以每次覆盖前先往
 * {@link NovelAiVerdictLog} 追加一条流水——详情面板靠它展示"这本书被谁判过几次"。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiVerdictWriter {

    /**
     * 一轮判定的结论三元组。
     *
     * <p>为什么需要两份：<b>明细</b>（{@code novel_ai_verdict}）代表「这个型号对这本书的
     * 当前结论」，命中 AI 之后不能被后续干净章覆盖；<b>流水</b>（{@code novel_ai_verdict_log}）
     * 代表「这一轮实际判成什么」，每一章都是独立证据。两者共用一份分数会让时间线上出现
     * 「10 章全 95%」的假象（实际只有 1 章命中），详情面板与加权平均都会被带偏。</p>
     *
     * @param flag   1=非 AI / 2=AI / 3=疑似 / 0=未完成（逐章未查完的中间步）
     * @param score  AI 概率（0–100）；null 表示这一轮没有证据（进度/失败），落库与展示都按「—」处理
     * @param reason 判定理由摘要
     */
    public record RoundVerdict(int flag, Double score, String reason) {
    }

    private final NovelAiVerdictRepository verdictRepository;
    private final NovelAiVerdictLogRepository verdictLogRepository;
    private final NovelService novelService;
    private final TransactionTemplate transactionTemplate;

    /**
     * 整本初筛结论：明细与主表同事务写入。
     *
     * @param bookId   书籍 ID
     * @param engine   引擎标识
     * @param model    实际型号
     * @param aiFlag   1/2/3
     * @param aiScore  概率（已保留两位小数）
     * @param reason   理由摘要
     * @param promptId 本次使用的提示词变体，可为 null
     * @param sampleMode 取样档位（free/member + 策略）
     * @param sampleSpan 实际采用的章号跨度
     */
    public void writeSweep(String bookId, String engine, String model, int aiFlag, double aiScore,
                           String reason, String promptId, String sampleMode, String sampleSpan) {
        writeSweep(bookId, engine, model, aiFlag, aiScore, reason, promptId, sampleMode, sampleSpan,
                NovelAiVerdictLog.KIND_SWEEP, 0);
    }

    /**
     * 整本判定落库，并指定流水动作。
     *
     * @param kind {@link NovelAiVerdictLog#KIND_SWEEP}（批量/单本初筛）
     *             或 {@link NovelAiVerdictLog#KIND_RERUN}（手点模型名重跑）
     */
    public void writeSweep(String bookId, String engine, String model, int aiFlag, double aiScore,
                           String reason, String promptId, String sampleMode, String sampleSpan,
                           String kind) {
        writeSweep(bookId, engine, model, aiFlag, aiScore, reason, promptId, sampleMode, sampleSpan, kind, 0);
    }

    /**
     * 整本判定落库，并记录这一轮实际采用的章数。
     *
     * @param sampleChapters 实际采用的章数（加权平均的权重）；0 表示未知
     */
    public void writeSweep(String bookId, String engine, String model, int aiFlag, double aiScore,
                           String reason, String promptId, String sampleMode, String sampleSpan,
                           String kind, int sampleChapters) {
        Runnable write = () -> transactionTemplate.executeWithoutResult(status -> {
            upsert(bookId, engine, model, new RoundVerdict(aiFlag, aiScore, reason), null, 0, promptId,
                    sampleMode, sampleSpan, kind, 0, sampleChapters, true);
            novelService.applyFreeVerdict(bookId, aiFlag, aiScore, engine);
        });
        try {
            write.run();
        } catch (DataIntegrityViolationException conflict) {
            // 并发判同一本同一型号（两个标签页 / 批量与单本同时）：另一线程已插入，
            // 重跑一次事务——这次会命中已存在的行并更新，而不是把整本记为失败
            log.warn("并发写入同一结论，改为更新已有行: bookId={}, engine={}, model={}", bookId, engine, model);
            write.run();
        }
    }

    /**
     * 逐章校验一步：明细与主表同事务写入。
     *
     * @param chaptersChecked 该型号在本批中已完成的章数（进度游标）
     * @param chapterNo       本次实际校验的章号（1-based），流水用它回答「查的是第几章」
     * @param aiHit           本次是否命中（决定主表是否覆盖综合结论）
     */
    public void writeVerify(String bookId, String engine, String model, int aiFlag, double aiScore,
                            String reason, int chaptersChecked, int chapterNo, String promptId, boolean aiHit,
                            String sampleMode) {
        // 逐章校验每轮只判一章：权重恒为 1，不需要调用方传
        writeVerify(bookId, engine, model, aiFlag, aiScore, reason, chaptersChecked, chapterNo, promptId, aiHit,
                sampleMode, 1);
    }

    /**
     * 逐章校验一步，并记录本轮实际校验的章数（通常为 1）。
     */
    public void writeVerify(String bookId, String engine, String model, int aiFlag, double aiScore,
                            String reason, int chaptersChecked, int chapterNo, String promptId, boolean aiHit,
                            String sampleMode, int sampleChapters) {
        writeVerify(bookId, engine, model, new RoundVerdict(aiFlag, aiScore, reason), null,
                chaptersChecked, chapterNo, promptId, aiHit, sampleMode, sampleChapters);
    }

    /**
     * 逐章校验一步：<b>明细与流水各记各的</b>。
     *
     * <p>命中 AI 之后，后续干净章不能撤销「这本书判过 AI」的结论，所以明细继续保留命中的
     * 分数与理由；但流水记录的是「这一章实际判成什么」，必须用本章真实分数，否则详情面板
     * 会显示「每章都是 95%」、加权平均也会被推到命中值。</p>
     *
     * @param detail 明细口径（命中后保持首次命中的分数与理由）
     * @param round  流水口径（本章实际判定的分数与理由）；null 表示与明细一致
     * @param aiHit  本次是否命中（决定主表是否覆盖综合结论）
     */
    public void writeVerify(String bookId, String engine, String model, RoundVerdict detail, RoundVerdict round,
                            int chaptersChecked, int chapterNo, String promptId, boolean aiHit,
                            String sampleMode) {
        writeVerify(bookId, engine, model, detail, round, chaptersChecked, chapterNo, promptId, aiHit,
                sampleMode, 1);
    }

    /**
     * 逐章校验一步，并显式指定流水权重章数（逐章恒为 1，保留参数是为了与初筛同构）。
     */
    public void writeVerify(String bookId, String engine, String model, RoundVerdict detail, RoundVerdict round,
                            int chaptersChecked, int chapterNo, String promptId, boolean aiHit,
                            String sampleMode, int sampleChapters) {
        Runnable write = () -> transactionTemplate.executeWithoutResult(status -> {
            upsert(bookId, engine, model, detail, round, chaptersChecked, promptId, sampleMode, null,
                    NovelAiVerdictLog.KIND_VERIFY, chapterNo, sampleChapters, true);
            novelService.applyManualVerifyVerdict(bookId, engine, detail.flag(), chaptersChecked,
                    detail.score(), aiHit);
        });
        try {
            write.run();
        } catch (DataIntegrityViolationException conflict) {
            // 两个标签页同时对同一型号逐章校验：find 都读到「没有」，后插的撞唯一键。
            // 与 writeSweep 同构重跑一次，这次命中已存在的行并更新；
            // 否则会冒到全局处理器、被当成「库文件被占用」回 503，排查方向全错
            log.warn("并发写入逐章结论，改为更新已有行: bookId={}, engine={}, model={}", bookId, engine, model);
            write.run();
        }
    }

    /**
     * 仅推进进度（试读/VIP 跳过、或已查完上限）：更新明细，不动主表综合结论。
     *
     * <p>流水固定记 {@code flag=0} 且不带分数与章数权重：这一章没有判定，它只是进度。
     * 原先流水沿用明细的 {@code keptHit ? 2} 与上一轮分数，会让前端把「试读跳过」那一行
     * 当成命中证据参与选行，详情里还会显示成「未完成 12%」——看起来像判过、只是没出结论。</p>
     */
    public void writeProgress(String bookId, String engine, String model, int aiFlag, double aiScore,
                              String reason, int chaptersChecked, int chapterNo, String promptId,
                              String sampleMode) {
        transactionTemplate.executeWithoutResult(status ->
                upsert(bookId, engine, model, new RoundVerdict(aiFlag, aiScore, reason),
                        new RoundVerdict(0, null, reason),
                        chaptersChecked, promptId, sampleMode, null,
                        NovelAiVerdictLog.KIND_PROGRESS, chapterNo, 0, false));
    }

    /**
     * 记一次失败的审核尝试：只追加流水，<b>不动明细与主表</b>。
     *
     * <p>为什么不能走 {@link #upsert}：明细行代表「这个型号对这本书的当前结论」，
     * 一次失败不该把已有结论覆盖成 {@code ai_flag=0}。失败的价值在时间线上——
     * 「这轮试过但没出结论」要看得见，结论本身保持上一次成功的结果。</p>
     *
     * <p>写入失败只记日志不抛：留痕失败不能让判定链路本身崩掉，
     * 与 {@code AiFaultGuard.recordFailure} 的取舍一致。</p>
     *
     * @param reason 失败原因摘要（会截断到列宽 200）
     * @param sampleMode 本轮取样档位，可为 null
     * @param sampleSpan 本轮取样跨度，可为 null
     * @param chapterNo 本轮校验的章号（逐章失败才有），0 表示无单章概念
     */
    public void writeFailed(String bookId, String engine, String model, String reason,
                            String sampleMode, String sampleSpan, int chapterNo) {
        try {
            appendLog(bookId, engine, model, 0, null, reason, 0, null,
                    sampleMode, sampleSpan, NovelAiVerdictLog.KIND_FAILED, chapterNo, 0);
        } catch (RuntimeException storeFailure) {
            log.warn("失败流水写入失败: bookId={}, engine={}, model={}, 原因={}",
                    bookId, engine, model, storeFailure.getMessage());
        }
    }

    private void upsert(String bookId, String engine, String model, RoundVerdict detail, RoundVerdict round,
                        int chaptersChecked, String promptId,
                        String sampleMode, String sampleSpan, String kind, int chapterNo, int sampleChapters,
                        boolean touchCheckedTime) {
        NovelAiVerdict verdictRow = verdictRepository.findByBookIdAndEngineAndModel(bookId, engine, model)
                .orElseGet(() -> {
                    NovelAiVerdict fresh = new NovelAiVerdict();
                    fresh.setBookId(bookId);
                    fresh.setEngine(engine);
                    fresh.setModel(model);
                    return fresh;
                });
        verdictRow.setAiFlag(detail.flag());
        verdictRow.setAiScore(detail.score());
        verdictRow.setReason(truncateReason(detail.reason()));
        verdictRow.setChaptersChecked(chaptersChecked);
        verdictRow.setPromptId(promptId);
        // 逐章行同样记档位：游标要靠它区分「这批进度属于哪套取样规则」
        if (sampleMode != null) {
            verdictRow.setSampleMode(sampleMode);
        }
        if (sampleSpan != null) {
            verdictRow.setSampleSpan(sampleSpan);
        }
        /* 纯进度写入不是一次判定：不刷新明细的「最近判定时间」，
           否则详情卡头看起来像刚判过。新行没有旧时间可用，仍要填（列非空） */
        boolean freshRow = verdictRow.getId() == null;
        if (touchCheckedTime || freshRow) {
            verdictRow.setCheckedTime(DateUtil.now());
        }
        verdictRepository.save(verdictRow);

        // 流水默认与明细同口径；逐章校验会显式传入"本章实际"的那一份
        RoundVerdict logged = round == null ? detail : round;
        appendLog(bookId, engine, model, logged.flag(), logged.score(), logged.reason(), chaptersChecked, promptId,
                sampleMode, sampleSpan, kind, chapterNo, sampleChapters);
    }

    /**
     * 追加一条判定流水并裁剪。
     *
     * <p>逐章校验不取样，此时 sampleMode/sampleSpan 传 null：沿用明细表里上一轮的档位，
     * 否则时间线上会出现「第 3 章校验，取样档位是空」这种断档。逐章那一轮"用了哪一章"
     * 由 {@code chapterNo} 单独记——写进 sampleSpan 会把整本初筛的取样跨度覆盖掉。</p>
     */
    private void appendLog(String bookId, String engine, String model, int aiFlag, Double aiScore,
                           String reason, int chaptersChecked, String promptId,
                           String sampleMode, String sampleSpan, String kind, int chapterNo, int sampleChapters) {
        NovelAiVerdictLog logRow = new NovelAiVerdictLog();
        logRow.setBookId(bookId);
        logRow.setEngine(engine);
        logRow.setModel(model);
        logRow.setAiFlag(aiFlag);
        logRow.setAiScore(aiScore);
        logRow.setReason(truncateReason(reason));
        logRow.setChaptersChecked(chaptersChecked);
        logRow.setPromptId(promptId);
        logRow.setSampleMode(sampleMode);
        logRow.setSampleSpan(sampleSpan);
        // 0 表示这一轮没有采用章节（进度/失败）：落 null，与「未知」区分开
        logRow.setSampleChapters(sampleChapters > 0 ? sampleChapters : null);
        // 只有逐章校验有单章概念：0 表示这一轮不是"查某一章"，落 null 而不是 0
        logRow.setChapterNo(chapterNo > 0 ? chapterNo : null);
        logRow.setKind(kind);
        logRow.setCheckedTime(DateUtil.now());
        verdictLogRepository.save(logRow);
        trimLog(bookId);
    }

    /** 保留最近 {@link NovelAiVerdictLog#KEEP_PER_BOOK} 轮：上限见常量注释，超出按新到旧裁剪。 */
    private void trimLog(String bookId) {
        /* 先数一下再决定要不要拉 id：逐章校验一轮会写 40 次，每次都把几百个主键捞回来
           只为判断「有没有超上限」不划算。count 只回一个数字，正常情况到这里就结束 */
        if (verdictLogRepository.countByBookId(bookId) <= NovelAiVerdictLog.KEEP_PER_BOOK) {
            return;
        }
        List<Long> ids = verdictLogRepository.findIdsByBookIdNewestFirst(bookId);
        if (ids.size() > NovelAiVerdictLog.KEEP_PER_BOOK) {
            verdictLogRepository.deleteAllByIdInBatch(ids.subList(NovelAiVerdictLog.KEEP_PER_BOOK, ids.size()));
        }
    }

    private String truncateReason(String reason) {
        if (reason == null) {
            return "";
        }
        return reason.length() > 200 ? reason.substring(0, 200) : reason;
    }
}
