package org.example.fqxs.service;

import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.entity.NovelAiVerdictLog;
import org.example.fqxs.repository.NovelAiVerdictLogRepository;
import org.example.fqxs.repository.NovelAiVerdictRepository;
import org.example.fqxs.repository.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 判定流水的读侧契约：一次只回有限轮次、失败行不带分数、章数缺省为 0。
 *
 * <p>这三个点各自对应一处口径：上限防止 500 条全量传输；失败行没有概率，
 * 回 0 会被前端当成「0% 非 AI」；{@code sample_chapters} 为空表示旧行，前端要靠 0 走兜底。</p>
 */
@ExtendWith(MockitoExtension.class)
class AiDetectStatusServiceTest {

    private static final String BOOK_ID = "7276384138653862966";

    @Mock
    private NovelRepository novelRepository;
    @Mock
    private NovelAiVerdictRepository verdictRepository;
    @Mock
    private NovelAiVerdictLogRepository verdictLogRepository;
    @Mock
    private AiEngineRegistry engineRegistry;

    private AiDetectStatusService service;

    @BeforeEach
    void setUp() {
        service = new AiDetectStatusService(novelRepository, verdictRepository, verdictLogRepository,
                engineRegistry, new AiDetectConfig());
    }

    @Test
    void verdictHistoryAsksForBoundedWindow() {
        when(verdictLogRepository.findByBookIdOrderByCheckedTimeDescIdDesc(eq(BOOK_ID), any(Pageable.class)))
                .thenReturn(List.of());

        service.verdictHistory(BOOK_ID);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(verdictLogRepository).findByBookIdOrderByCheckedTimeDescIdDesc(eq(BOOK_ID), captor.capture());
        // 每本最多留 500 条，但面板只摊开 100 轮：一次回 200 留一倍余量
        assertThat(captor.getValue().getPageSize()).isEqualTo(200);
        assertThat(captor.getValue().getPageNumber()).isZero();
    }

    @Test
    void failedRowKeepsNullScoreAndZeroChapters() {
        when(verdictLogRepository.findByBookIdOrderByCheckedTimeDescIdDesc(eq(BOOK_ID), any(Pageable.class)))
                .thenReturn(List.of(failedRow()));

        AiDetectService.VerdictLogEntry entry = service.verdictHistory(BOOK_ID).get(0);

        // 失败没有概率：保持 null，前端显示「—」；回 0 会被读成「0% 非 AI」
        assertThat(entry.aiScore()).isNull();
        assertThat(entry.aiFlag()).isZero();
        assertThat(entry.kind()).isEqualTo(NovelAiVerdictLog.KIND_FAILED);
        assertThat(entry.sampleChapters()).isZero();
        assertThat(entry.reason()).isEqualTo("HTTP 429 额度耗尽");
    }

    @Test
    void blankBookIdShortCircuits() {
        assertThat(service.verdictHistory("  ")).isEmpty();
        assertThat(service.verdictHistory(null)).isEmpty();
    }

    private static NovelAiVerdictLog failedRow() {
        NovelAiVerdictLog row = new NovelAiVerdictLog();
        row.setBookId(BOOK_ID);
        row.setEngine("glm-4-flash");
        row.setModel("glm-4-flash");
        row.setAiFlag(0);
        row.setAiScore(null);
        row.setReason("HTTP 429 额度耗尽");
        row.setKind(NovelAiVerdictLog.KIND_FAILED);
        row.setChaptersChecked(0);
        row.setSampleChapters(null);
        row.setCheckedTime(LocalDateTime.of(2026, 10, 26, 9, 4));
        return row;
    }
}
