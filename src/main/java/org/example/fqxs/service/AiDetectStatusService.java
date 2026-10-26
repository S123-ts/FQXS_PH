package org.example.fqxs.service;

import lombok.RequiredArgsConstructor;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.entity.AiEngine;
import org.example.fqxs.entity.NovelAiVerdict;
import org.example.fqxs.entity.NovelAiVerdictLog;
import org.example.fqxs.repository.NovelAiVerdictLogRepository;
import org.example.fqxs.repository.NovelAiVerdictRepository;
import org.example.fqxs.repository.NovelRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 判定状态与逐引擎结论查询：纯读，不触发外部调用。
 *
 * <p>从 AiDetectServiceImpl 拆出，供 `GET /api/ai/status` 与 `GET /api/ai/verdicts` 使用。</p>
 */
@Component
@RequiredArgsConstructor
public class AiDetectStatusService {

    private static final DateTimeFormatter VERDICT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 详情面板一次最多回多少轮。
     *
     * <p>每本最多留 {@link NovelAiVerdictLog#KEEP_PER_BOOK} 条，但面板只摊开最近 100 轮；
     * 取 200 留一倍余量，既能把整批 500 条的传输砍掉六成，也够「另有 N 轮」的提示有数可报。</p>
     */
    private static final int HISTORY_LIMIT = 200;

    private final NovelRepository novelRepository;
    private final NovelAiVerdictRepository verdictRepository;
    private final NovelAiVerdictLogRepository verdictLogRepository;
    private final AiEngineRegistry engineRegistry;
    private final AiDetectConfig aiDetectConfig;

    /**
     * 判定能力与分布快照。
     *
     * @return 快照
     */
    public AiDetectService.StatusView status() {
        Map<String, Long> pendingByEngine = new LinkedHashMap<>();
        // 只统计已配置的引擎：没 Key 的引擎在页面上是禁用态，它的待判数没有意义
        for (String engine : engineRegistry.availableFreeEngines()) {
            pendingByEngine.put(engine,
                    novelRepository.countBookIdsMissingEngineVerdict(engine, engineRegistry.modelsOf(engine)));
        }
        Map<String, List<String>> modelPools = new LinkedHashMap<>();
        for (String engine : engineRegistry.availableFreeEngines()) {
            modelPools.put(engine, engineRegistry.modelsOf(engine));
        }
        // 熔断候选：只有 autoBan=true 的引擎允许被自动切换
        List<String> bannableEngines = engineRegistry.availableFreeEngines().stream()
                .filter(id -> engineRegistry.find(id).map(AiEngine::isAutoBan).orElse(false))
                .toList();
        return new AiDetectService.StatusView(
                aiDetectConfig.isEnabled(),
                engineRegistry.availableFreeEngines(),
                pendingByEngine,
                novelRepository.countDistinctBooksByAiFlag(2),
                novelRepository.countDistinctBooksByAiFlag(3),
                engineRegistry.availableVerifyEngines(),
                aiDetectConfig.getVerifyMaxChapters(),
                modelPools,
                bannableEngines);
    }

    /**
     * 批量取逐引擎结论。
     *
     * @param bookIds 书籍 ID 列表
     * @return 结论列表
     */
    public List<AiDetectService.EngineVerdict> verdictsForBooks(List<String> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            return List.of();
        }
        List<AiDetectService.EngineVerdict> verdicts = new ArrayList<>();
        for (NovelAiVerdict verdictRow : verdictRepository.findByBookIdInOrderByCheckedTimeDescIdDesc(bookIds)) {
            verdicts.add(new AiDetectService.EngineVerdict(
                    verdictRow.getBookId(),
                    verdictRow.getEngine(),
                    verdictRow.getModel(),
                    verdictRow.getPromptId() == null ? "" : verdictRow.getPromptId(),
                    verdictRow.getSampleMode() == null ? "" : verdictRow.getSampleMode(),
                    verdictRow.getSampleSpan() == null ? "" : verdictRow.getSampleSpan(),
                    verdictRow.getAiFlag(),
                    verdictRow.getAiScore() == null ? 0 : verdictRow.getAiScore(),
                    verdictRow.getReason() == null ? "" : verdictRow.getReason(),
                    verdictRow.getCheckedTime() == null ? "" : VERDICT_TIME_FORMAT.format(verdictRow.getCheckedTime()),
                    verdictRow.getChaptersChecked()));
        }
        return verdicts;
    }

    /**
     * 单本书的判定流水，新的在前。
     *
     * <p>流水表只追加，所以同一型号的反复重判、逐章校验的每一步都在里面——
     * 这是详情面板「多次校验」的数据来源。</p>
     *
     * @param bookId 书籍 ID
     * @return 流水列表；bookId 为空返回空列表
     */
    public List<AiDetectService.VerdictLogEntry> verdictHistory(String bookId) {
        if (bookId == null || bookId.isBlank()) {
            return List.of();
        }
        List<AiDetectService.VerdictLogEntry> history = new ArrayList<>();
        for (NovelAiVerdictLog logRow : verdictLogRepository
                .findByBookIdOrderByCheckedTimeDescIdDesc(bookId, PageRequest.of(0, HISTORY_LIMIT))) {
            history.add(new AiDetectService.VerdictLogEntry(
                    logRow.getBookId(),
                    logRow.getEngine(),
                    logRow.getModel(),
                    logRow.getPromptId() == null ? "" : logRow.getPromptId(),
                    logRow.getSampleMode() == null ? "" : logRow.getSampleMode(),
                    logRow.getSampleSpan() == null ? "" : logRow.getSampleSpan(),
                    logRow.getSampleChapters() == null ? 0 : logRow.getSampleChapters(),
                    logRow.getAiFlag(),
                    // 失败行没有概率：保持 null，前端显示「—」而不是 0%
                    logRow.getAiScore(),
                    logRow.getReason() == null ? "" : logRow.getReason(),
                    VERDICT_TIME_FORMAT.format(logRow.getCheckedTime()),
                    logRow.getChaptersChecked(),
                    logRow.getKind()));
        }
        return history;
    }
}
