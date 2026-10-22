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
                NovelAiVerdictLog.KIND_SWEEP);
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
        Runnable write = () -> transactionTemplate.executeWithoutResult(status -> {
            upsert(bookId, engine, model, aiFlag, aiScore, reason, 0, promptId, sampleMode, sampleSpan, kind);
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
                            String reason, int chaptersChecked, int chapterNo, String promptId, boolean aiHit) {
        transactionTemplate.executeWithoutResult(status -> {
            upsert(bookId, engine, model, aiFlag, aiScore, reason, chaptersChecked, promptId, null, null,
                    NovelAiVerdictLog.KIND_VERIFY, chapterNo);
            novelService.applyManualVerifyVerdict(bookId, engine, aiFlag, chaptersChecked, aiScore, aiHit);
        });
    }

    /**
     * 仅推进进度（已查完上限）：更新明细，不动主表综合结论。
     */
    public void writeProgress(String bookId, String engine, String model, int aiFlag, double aiScore,
                              String reason, int chaptersChecked, int chapterNo, String promptId) {
        transactionTemplate.executeWithoutResult(status ->
                upsert(bookId, engine, model, aiFlag, aiScore, reason, chaptersChecked, promptId, null, null,
                        NovelAiVerdictLog.KIND_PROGRESS, chapterNo));
    }

    private void upsert(String bookId, String engine, String model, int aiFlag, double aiScore,
                        String reason, int chaptersChecked, String promptId,
                        String sampleMode, String sampleSpan, String kind) {
        upsert(bookId, engine, model, aiFlag, aiScore, reason, chaptersChecked, promptId,
                sampleMode, sampleSpan, kind, 0);
    }

    private void upsert(String bookId, String engine, String model, int aiFlag, double aiScore,
                        String reason, int chaptersChecked, String promptId,
                        String sampleMode, String sampleSpan, String kind, int chapterNo) {
        NovelAiVerdict verdictRow = verdictRepository.findByBookIdAndEngineAndModel(bookId, engine, model)
                .orElseGet(() -> {
                    NovelAiVerdict fresh = new NovelAiVerdict();
                    fresh.setBookId(bookId);
                    fresh.setEngine(engine);
                    fresh.setModel(model);
                    return fresh;
                });
        verdictRow.setAiFlag(aiFlag);
        verdictRow.setAiScore(aiScore);
        verdictRow.setReason(truncateReason(reason));
        verdictRow.setChaptersChecked(chaptersChecked);
        verdictRow.setPromptId(promptId);
        // 逐章校验不取样，不要用它把整本初筛记下的档位擦掉
        if (sampleMode != null) {
            verdictRow.setSampleMode(sampleMode);
        }
        if (sampleSpan != null) {
            verdictRow.setSampleSpan(sampleSpan);
        }
        verdictRow.setCheckedTime(DateUtil.now());
        verdictRepository.save(verdictRow);

        appendLog(bookId, engine, model, aiFlag, aiScore, reason, chaptersChecked, promptId,
                sampleMode, sampleSpan, kind, chapterNo);
    }

    /**
     * 追加一条判定流水并裁剪。
     *
     * <p>逐章校验不取样，此时 sampleMode/sampleSpan 传 null：沿用明细表里上一轮的档位，
     * 否则时间线上会出现「第 3 章校验，取样档位是空」这种断档。逐章那一轮"用了哪一章"
     * 由 {@code chapterNo} 单独记——写进 sampleSpan 会把整本初筛的取样跨度覆盖掉。</p>
     */
    private void appendLog(String bookId, String engine, String model, int aiFlag, double aiScore,
                           String reason, int chaptersChecked, String promptId,
                           String sampleMode, String sampleSpan, String kind, int chapterNo) {
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
        // 只有逐章校验有单章概念：0 表示这一轮不是"查某一章"，落 null 而不是 0
        logRow.setChapterNo(chapterNo > 0 ? chapterNo : null);
        logRow.setKind(kind);
        logRow.setCheckedTime(DateUtil.now());
        verdictLogRepository.save(logRow);
        trimLog(bookId);
    }

    /** 保留最近 {@link NovelAiVerdictLog#KEEP_PER_BOOK} 轮：详情面板放不下更多，也没人会翻更远。 */
    private void trimLog(String bookId) {
        List<Long> ids = verdictLogRepository.findIdsByBookIdNewestFirst(bookId);
        if (ids.size() <= NovelAiVerdictLog.KEEP_PER_BOOK) {
            return;
        }
        verdictLogRepository.deleteAllByIdInBatch(ids.subList(NovelAiVerdictLog.KEEP_PER_BOOK, ids.size()));
    }

    private String truncateReason(String reason) {
        if (reason == null) {
            return "";
        }
        return reason.length() > 200 ? reason.substring(0, 200) : reason;
    }
}
