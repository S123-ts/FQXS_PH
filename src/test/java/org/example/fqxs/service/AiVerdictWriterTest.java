package org.example.fqxs.service;

import org.example.fqxs.entity.NovelAiVerdict;
import org.example.fqxs.entity.NovelAiVerdictLog;
import org.example.fqxs.repository.NovelAiVerdictLogRepository;
import org.example.fqxs.repository.NovelAiVerdictRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 判定流水：每一次「覆盖明细」都必须先留痕，否则详情面板回答不了
 * 「这本书被谁判过几次」。同时守住每本保留上限，避免批量初筛把表撑爆。
 */
@ExtendWith(MockitoExtension.class)
class AiVerdictWriterTest {

    private static final String BOOK_ID = "7276384138653862966";
    private static final String GLM = "glm-4-flash";
    private static final String MODEL = "glm-4-flash";

    @Mock
    private NovelAiVerdictRepository verdictRepository;
    @Mock
    private NovelAiVerdictLogRepository verdictLogRepository;
    @Mock
    private NovelService novelService;
    @Mock
    private TransactionTemplate transactionTemplate;

    private AiVerdictWriter writer;

    @BeforeEach
    void setUp() {
        writer = new AiVerdictWriter(verdictRepository, verdictLogRepository, novelService, transactionTemplate);
        // 事务模板在本类里只是「怎么一致地写」，测的是写入内容，直接就地执行
        doAnswer(invocation -> {
            Consumer<org.springframework.transaction.TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        lenient().when(verdictRepository.findByBookIdAndEngineAndModel(any(), any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(verdictLogRepository.findIdsByBookIdNewestFirst(any()))
                .thenReturn(List.of(1L));
    }

    private NovelAiVerdictLog captureLog() {
        ArgumentCaptor<NovelAiVerdictLog> captor = ArgumentCaptor.forClass(NovelAiVerdictLog.class);
        verify(verdictLogRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void sweepWritesOneLogRowWithSampleSpec() {
        writer.writeSweep(BOOK_ID, GLM, MODEL, 2, 86.5, "通篇句式机械", "p1-total",
                "member:mid_recent", "42,57");

        NovelAiVerdictLog row = captureLog();
        assertThat(row.getBookId()).isEqualTo(BOOK_ID);
        assertThat(row.getEngine()).isEqualTo(GLM);
        assertThat(row.getModel()).isEqualTo(MODEL);
        assertThat(row.getKind()).isEqualTo(NovelAiVerdictLog.KIND_SWEEP);
        assertThat(row.getAiFlag()).isEqualTo(2);
        assertThat(row.getAiScore()).isEqualTo(86.5);
        assertThat(row.getSampleMode()).isEqualTo("member:mid_recent");
        assertThat(row.getSampleSpan()).isEqualTo("42,57");
        assertThat(row.getChaptersChecked()).isZero();
        assertThat(row.getCheckedTime()).isNotNull();
    }

    @Test
    void rerunIsRecordedWithItsOwnKind() {
        // 手点模型名重跑与批量初筛走同一条落库路径，只有 kind 不同：
        // 详情面板据此把「人手动发起的那一轮」和批量初筛区分开
        writer.writeSweep(BOOK_ID, GLM, MODEL, 3, 61.5, "手点重跑", "p1-total",
                "official:head", "1-10", NovelAiVerdictLog.KIND_RERUN);

        NovelAiVerdictLog row = captureLog();
        assertThat(row.getKind()).isEqualTo(NovelAiVerdictLog.KIND_RERUN);
        assertThat(row.getSampleMode()).isEqualTo("official:head");
        assertThat(row.getSampleSpan()).isEqualTo("1-10");
        assertThat(row.getChaptersChecked()).isZero();
    }

    @Test
    void verifyWritesOneRowPerChapterWithProgress() {
        writer.writeVerify(BOOK_ID, GLM, MODEL, 3, 62.0, "第 4 章可疑", 4, 4, "p2-dialogue", false);

        NovelAiVerdictLog row = captureLog();
        assertThat(row.getKind()).isEqualTo(NovelAiVerdictLog.KIND_VERIFY);
        assertThat(row.getChaptersChecked()).isEqualTo(4);
        // 进度 4 与章号 4 在 head 档位下巧合相等；recent/random 下必须靠 chapterNo 才说得清
        assertThat(row.getChapterNo()).isEqualTo(4);
        assertThat(row.getAiScore()).isEqualTo(62.0);
        assertThat(row.getPromptId()).isEqualTo("p2-dialogue");
    }

    @Test
    void progressStepUsesProgressKind() {
        writer.writeProgress(BOOK_ID, GLM, MODEL, 1, 18.0, "", 10, 10, "p1-total");

        NovelAiVerdictLog row = captureLog();
        assertThat(row.getKind()).isEqualTo(NovelAiVerdictLog.KIND_PROGRESS);
        assertThat(row.getChaptersChecked()).isEqualTo(10);
        assertThat(row.getChapterNo()).isEqualTo(10);
    }

    @Test
    void sweepLeavesChapterNoEmptyBecauseItHasNoSingleChapter() {
        writer.writeSweep(BOOK_ID, GLM, MODEL, 2, 80.0, "整本初筛", "p1-total", "official:head", "1-10");

        NovelAiVerdictLog row = captureLog();
        // 初筛没有"单章"概念：章号留空，跨度在 sampleSpan
        assertThat(row.getChapterNo()).isNull();
        assertThat(row.getSampleSpan()).isEqualTo("1-10");
    }

    @Test
    void repeatedVerdictsOverwriteDetailButKeepEveryLogRow() {
        NovelAiVerdict existing = new NovelAiVerdict();
        existing.setBookId(BOOK_ID);
        existing.setEngine(GLM);
        existing.setModel(MODEL);
        when(verdictRepository.findByBookIdAndEngineAndModel(BOOK_ID, GLM, MODEL))
                .thenReturn(Optional.of(existing));

        writer.writeSweep(BOOK_ID, GLM, MODEL, 2, 90.0, "第二次重判", "p1-total", null, null);
        writer.writeSweep(BOOK_ID, GLM, MODEL, 1, 12.0, "第三次重判", "p1-total", null, null);

        ArgumentCaptor<NovelAiVerdictLog> captor = ArgumentCaptor.forClass(NovelAiVerdictLog.class);
        verify(verdictLogRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        // 明细只有一行（被覆盖），流水两条：这正是详情面板能列出「多次校验」的原因
        assertThat(captor.getAllValues()).extracting(NovelAiVerdictLog::getAiScore)
                .containsExactly(90.0, 12.0);
    }

    @Test
    void trimsOldestRowsBeyondKeepLimit() {
        List<Long> ids = new ArrayList<>(LongStream.rangeClosed(1, NovelAiVerdictLog.KEEP_PER_BOOK + 5)
                .boxed().toList());
        // 仓库按「新到旧」返回，尾部 5 条是最老的
        List<Long> newestFirst = new ArrayList<>(ids);
        java.util.Collections.reverse(newestFirst);
        when(verdictLogRepository.findIdsByBookIdNewestFirst(BOOK_ID)).thenReturn(newestFirst);

        writer.writeSweep(BOOK_ID, GLM, MODEL, 2, 80.0, "", null, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(verdictLogRepository).deleteAllByIdInBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(5);
        assertThat(captor.getValue()).containsExactly(5L, 4L, 3L, 2L, 1L);
    }

    @Test
    void keepsAllRowsUnderLimit() {
        when(verdictLogRepository.findIdsByBookIdNewestFirst(BOOK_ID))
                .thenReturn(List.of(3L, 2L, 1L));

        writer.writeSweep(BOOK_ID, GLM, MODEL, 2, 80.0, "", null, null, null);

        verify(verdictLogRepository, never()).deleteAllByIdInBatch(anyList());
    }

    @Test
    void truncatesReasonToColumnLength() {
        writer.writeSweep(BOOK_ID, GLM, MODEL, 2, 80.0, "错".repeat(300), null, null, null);

        assertThat(captureLog().getReason()).hasSize(200);
    }
}
