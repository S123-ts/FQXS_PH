package org.example.fqxs.service;

import org.example.fqxs.client.AiDetectClient;
import org.example.fqxs.client.ThirdPartyChapterClient;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.entity.AiEngine;
import org.example.fqxs.entity.Novel;
import org.example.fqxs.entity.NovelAiVerdict;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.exception.OperationConflictException;
import org.example.fqxs.model.AiVerdict;
import org.example.fqxs.model.ChapterSample;
import org.example.fqxs.model.SampleSpec;
import org.example.fqxs.repository.NovelAiVerdictRepository;
import org.example.fqxs.repository.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 逐章校验契约：
 * 未命中推进一章；命中后继续把剩余章节查完；同一模型同一章节不重复校验；
 * 型号池里当前型号查完自动换下一个；试读章跳过判定但仍推进进度。
 */
@ExtendWith(MockitoExtension.class)
class AiVerifyServiceTest {

    private static final String ENGINE = AiDetectConfig.ENGINE_GLM;
    private static final String MODEL_A = "glm-4-flash";
    private static final String MODEL_B = "glm-4.7-flash";
    private static final String VERIFY_A = MODEL_A + AiVerifyService.VERIFY_MODEL_SUFFIX;
    private static final String VERIFY_B = MODEL_B + AiVerifyService.VERIFY_MODEL_SUFFIX;

    /** 10 章的可读区：与默认取样（前 10 章）一致。 */
    private static final List<String> ITEM_IDS = List.of(
            "c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8", "c9", "c10");

    private static final List<Integer> INDICES = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

    @Mock
    private AiDetectClient aiDetectClient;

    @Mock
    private ChapterContentService chapterContentService;

    @Mock
    private ThirdPartyChapterClient thirdPartyChapterClient;

    @Mock
    private AiSampleCollector sampleCollector;

    @Mock
    private AiEngineRegistry engineRegistry;

    @Mock
    private AiEngineRouter engineRouter;

    @Mock
    private NovelRepository novelRepository;

    @Mock
    private NovelAiVerdictRepository verdictRepository;

    @Mock
    private AiVerdictWriter verdictWriter;

    @Mock
    private AiFaultGuard faultGuard;

    private AiDetectConfig config;

    private AiVerifyService service;

    @BeforeEach
    void setUp() {
        config = new AiDetectConfig();
        service = new AiVerifyService(aiDetectClient, thirdPartyChapterClient, chapterContentService, sampleCollector,
                config, engineRegistry, engineRouter, novelRepository, verdictRepository, verdictWriter, faultGuard);
        AiEngine engine = AiEngine.builder()
                .id(ENGINE).name("智谱 GLM").baseUrl("https://example.test/v1/chat/completions")
                .model(MODEL_A).protocol("openai_chat").builtin(true).enabled(true).autoBan(true).priority(0)
                .build();
        Novel novelRow = new Novel();
        novelRow.setBookId("b1");

        lenient().when(engineRouter.resolveAvoiding(anyString(), anyList())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(engineRegistry.modelsOf(anyString())).thenReturn(List.of(MODEL_A));
        lenient().when(novelRepository.findAllByBookId("b1")).thenReturn(List.of(novelRow));
        lenient().when(sampleCollector.plan(eq("b1"), any()))
                .thenReturn(new AiSampleCollector.ChapterPlan(ITEM_IDS, INDICES, 10, 300, false));
        lenient().when(chapterContentService.getOrFetch(anyString(), anyString(), anyInt(), any()))
                .thenReturn(new ChapterSample("c1", "第1章", "正".repeat(800), 800));
    }

    @Test
    void advancesOneChapterWithoutHit() {
        when(verdictRepository.findByBookIdAndEngineAndModel("b1", ENGINE, VERIFY_A))
                .thenReturn(Optional.empty());
        when(aiDetectClient.detectFree(eq(ENGINE), eq(MODEL_A), anyString()))
                .thenReturn(new AiVerdict(12, ENGINE, "不像 AI", null));

        AiDetectService.VerifyStepResult result = service.stepVerify("b1", ENGINE, null);

        assertThat(result.aiHit()).isFalse();
        assertThat(result.finished()).isFalse();
        // 还有 9 章没查：整批远未结束
        assertThat(result.allDone()).isFalse();
        assertThat(result.chapterIndex()).isEqualTo(1);
        assertThat(result.stepIndex()).isEqualTo(1);
        assertThat(result.totalChapters()).isEqualTo(10);
        verify(verdictWriter).writeVerify(eq("b1"), eq(ENGINE), eq(VERIFY_A),
                eq(round(0, 12.0, "不像 AI")), eq(round(0, 12.0, "不像 AI")),
                eq(1), eq(1), isNull(), eq(false), eq("official:head"));
        // 成功要清零连续失败计数，否则零星失败会累积到阈值、把引擎误停用
        verify(faultGuard).recordSuccess(ENGINE);
    }

    @Test
    void keepsGoingAfterHitInsteadOfStopping() {
        when(verdictRepository.findByBookIdAndEngineAndModel("b1", ENGINE, VERIFY_A))
                .thenReturn(Optional.empty());
        when(aiDetectClient.detectFree(eq(ENGINE), eq(MODEL_A), anyString()))
                .thenReturn(new AiVerdict(88, ENGINE, "明显 AI", null));

        AiDetectService.VerifyStepResult result = service.stepVerify("b1", ENGINE, null);

        assertThat(result.aiHit()).isTrue();
        // 命中不再是终态：只查了 1/10，仍可继续
        assertThat(result.finished()).isFalse();
        verify(verdictWriter).writeVerify(eq("b1"), eq(ENGINE), eq(VERIFY_A),
                eq(round(2, 88.0, "明显 AI")), eq(round(2, 88.0, "明显 AI")),
                eq(1), eq(1), isNull(), eq(true), eq("official:head"));
    }

    @Test
    void keepsHitScoreWhenLaterChapterIsClean() {
        NovelAiVerdict hitRow = row(2, 88.0, "首次命中", 1);
        when(verdictRepository.findByBookIdAndEngineAndModel("b1", ENGINE, VERIFY_A))
                .thenReturn(Optional.of(hitRow));
        when(chapterContentService.getOrFetch(eq("b1"), eq("c2"), eq(1), any()))
                .thenReturn(new ChapterSample("c2", "第2章", "正".repeat(800), 800));
        when(aiDetectClient.detectFree(eq(ENGINE), eq(MODEL_A), anyString()))
                .thenReturn(new AiVerdict(10, ENGINE, "这一章像人写", null));

        AiDetectService.VerifyStepResult result = service.stepVerify("b1", ENGINE, null);

        assertThat(result.chapterIndex()).isEqualTo(2);
        // 已判过 AI 的结论不能被后续"干净章"抹掉，明细分数保留首次命中的 88
        assertThat(result.aiScore()).isEqualTo(88.0);
        /* 流水必须记本章实际的 10%：明细与流水共用一份分数会让详情面板显示
           「每章都 88%」，加权平均也会被推到命中值（这一章其实只判了 10%） */
        verify(verdictWriter).writeVerify(eq("b1"), eq(ENGINE), eq(VERIFY_A),
                eq(round(2, 88.0, "首次命中")), eq(round(0, 10.0, "这一章像人写")),
                eq(2), eq(2), isNull(), eq(false), eq("official:head"));
    }

    @Test
    void finishesWhenLastChapterIsChecked() {
        NovelAiVerdict lastRow = row(1, 5.0, "前面都像人写", 9);
        when(verdictRepository.findByBookIdAndEngineAndModel("b1", ENGINE, VERIFY_A))
                .thenReturn(Optional.of(lastRow));
        when(chapterContentService.getOrFetch(eq("b1"), eq("c10"), eq(9), any()))
                .thenReturn(new ChapterSample("c10", "第10章", "正".repeat(800), 800));
        when(aiDetectClient.detectFree(eq(ENGINE), eq(MODEL_A), anyString()))
                .thenReturn(new AiVerdict(9, ENGINE, "像人写", null));

        AiDetectService.VerifyStepResult result = service.stepVerify("b1", ENGINE, null);

        assertThat(result.chapterIndex()).isEqualTo(10);
        assertThat(result.finished()).isTrue();
        // 单个型号查完 ≠ 整批结束：型号池里可能还有别的型号没查，allDone 只能由"无可推进型号"给出
        assertThat(result.allDone()).isFalse();
        verify(verdictWriter).writeVerify(eq("b1"), eq(ENGINE), eq(VERIFY_A),
                eq(round(1, 9.0, "像人写")), eq(round(1, 9.0, "像人写")),
                eq(10), eq(10), isNull(), eq(false), eq("official:head"));
    }

    @Test
    void switchesToNextModelWhenCurrentBatchIsDone() {
        // 型号 A 已把 10 章查完，型号 B 还没开始：应自动换 B，而不是重复查 A
        when(engineRegistry.modelsOf(ENGINE)).thenReturn(List.of(MODEL_A, MODEL_B));
        // 型号池会 shuffle，A/B 谁先被查到不确定，两条打桩都按 lenient
        lenient().when(verdictRepository.findByBookIdAndEngineAndModel("b1", ENGINE, VERIFY_A))
                .thenReturn(Optional.of(row(2, 80.0, "A 已判 AI", 10)));
        lenient().when(verdictRepository.findByBookIdAndEngineAndModel("b1", ENGINE, VERIFY_B))
                .thenReturn(Optional.empty());
        when(aiDetectClient.detectFree(eq(ENGINE), eq(MODEL_B), anyString()))
                .thenReturn(new AiVerdict(20, ENGINE, "B 看像人写", null));

        AiDetectService.VerifyStepResult result = service.stepVerify("b1", ENGINE, null);

        assertThat(result.model()).isEqualTo(MODEL_B);
        assertThat(result.chapterIndex()).isEqualTo(1);
        verify(verdictWriter).writeVerify(eq("b1"), eq(ENGINE), eq(VERIFY_B),
                eq(round(0, 20.0, "B 看像人写")), eq(round(0, 20.0, "B 看像人写")),
                eq(1), eq(1), isNull(), eq(false), eq("official:head"));
        verify(verdictWriter, never()).writeVerify(eq("b1"), eq(ENGINE), eq(VERIFY_A),
                any(AiVerdictWriter.RoundVerdict.class), any(), anyInt(), anyInt(), any(),
                any(Boolean.class), any());
    }

    @Test
    void reportsFinishedWithoutRefetchingWhenAllModelsDone() {
        when(engineRegistry.modelsOf(ENGINE)).thenReturn(List.of(MODEL_A, MODEL_B));
        lenient().when(verdictRepository.findByBookIdAndEngineAndModel("b1", ENGINE, VERIFY_A))
                .thenReturn(Optional.of(row(2, 80.0, "A 已判 AI", 10)));
        lenient().when(verdictRepository.findByBookIdAndEngineAndModel("b1", ENGINE, VERIFY_B))
                .thenReturn(Optional.of(row(1, 10.0, "B 看像人写", 10)));

        AiDetectService.VerifyStepResult result = service.stepVerify("b1", ENGINE, null);

        assertThat(result.finished()).isTrue();
        // 前端靠这个标志停掉整批循环，不能只判 finished（单型号查完也会是 true）
        assertThat(result.allDone()).isTrue();
        assertThat(result.aiHit()).isFalse();
        assertThat(result.message()).contains("都已查完");
        // 没有更多章节可查：不该再抓正文、也不该再写任何结论
        verifyNoInteractions(chapterContentService);
        verifyNoInteractions(aiDetectClient);
    }

    @Test
    void restartsCursorWhenSampleStrategyChanges() {
        // 旧进度属于 recent 档（已查 5 章）；本次按默认 head 查时不能沿用，
        // 否则会从第 6 章开始，前 5 章被静默跳过
        NovelAiVerdict recentRow = row(1, 10.0, "旧策略进度", 5);
        recentRow.setSampleMode("third:recent");
        when(verdictRepository.findByBookIdAndEngineAndModel("b1", ENGINE, VERIFY_A))
                .thenReturn(Optional.of(recentRow));
        when(aiDetectClient.detectFree(eq(ENGINE), eq(MODEL_A), anyString()))
                .thenReturn(new AiVerdict(15, ENGINE, "像人写", null));

        AiDetectService.VerifyStepResult result = service.stepVerify("b1", ENGINE, null);

        assertThat(result.chapterIndex()).isEqualTo(1);
        verify(verdictWriter).writeVerify(eq("b1"), eq(ENGINE), eq(VERIFY_A),
                eq(round(0, 15.0, "像人写")), eq(round(0, 15.0, "像人写")),
                eq(1), eq(1), isNull(), eq(false), eq("official:head"));
    }

    @Test
    void skipsPreviewChapterButStillAdvances() {
        when(verdictRepository.findByBookIdAndEngineAndModel("b1", ENGINE, VERIFY_A))
                .thenReturn(Optional.empty());
        // 试读片段：字数低于 min-chapter-chars
        when(chapterContentService.getOrFetch(eq("b1"), eq("c1"), eq(0), any()))
                .thenReturn(new ChapterSample("c1", "第1章", "短", 1));

        AiDetectService.VerifyStepResult result = service.stepVerify("b1", ENGINE, null);

        assertThat(result.chapterSkipped()).isTrue();
        assertThat(result.stepIndex()).isEqualTo(1);
        assertThat(result.finished()).isFalse();
        // 跳过判定，但进度要推进，否则同一章会被反复请求
        verify(verdictWriter).writeProgress("b1", ENGINE, VERIFY_A, 0, 0.0, "", 1, 1, null, "official:head");
        verifyNoInteractions(aiDetectClient);
    }

    @Test
    void fetchFailureLeavesFailedLogWithoutAdvancingCursor() {
        when(verdictRepository.findByBookIdAndEngineAndModel("b1", ENGINE, VERIFY_A))
                .thenReturn(Optional.empty());
        when(chapterContentService.getOrFetch(eq("b1"), eq("c1"), eq(0), any()))
                .thenThrow(new CrawlException("上游 HTTP 500", 500, "boom"));

        assertThatThrownBy(() -> service.stepVerify("b1", ENGINE, null))
                .isInstanceOf(CrawlException.class);

        // 取文失败要留证，但不能当「试读跳过」推进游标：这批章还能重新点一次
        verify(verdictWriter).writeFailed("b1", ENGINE, VERIFY_A, "上游 HTTP 500", "official:head", null, 1);
        verify(verdictWriter, never()).writeProgress(any(), any(), any(), anyInt(), anyDouble(), any(), anyInt(),
                anyInt(), any(), any());
        verify(verdictWriter, never()).writeVerify(any(), any(), any(), any(AiVerdictWriter.RoundVerdict.class),
                any(), anyInt(), anyInt(), any(), any(Boolean.class), any());
        verifyNoInteractions(aiDetectClient);
        // 取文失败是线路问题，不是引擎故障：不能累计熔断把引擎停掉
        verifyNoInteractions(faultGuard);
    }

    @Test
    void thirdPartyFetchFailureAlsoLeavesFailedLog() {
        when(sampleCollector.plan(eq("b1"), any()))
                .thenReturn(new AiSampleCollector.ChapterPlan(ITEM_IDS, INDICES, 10, 300, true));
        when(verdictRepository.findByBookIdAndEngineAndModel("b1", ENGINE, VERIFY_A))
                .thenReturn(Optional.empty());
        when(thirdPartyChapterClient.fetchChapter("c1"))
                .thenThrow(new CrawlException("第三方 502", 502, "bad gateway"));

        assertThatThrownBy(() -> service.stepVerify("b1", ENGINE, SampleSpec.of(SampleSpec.RECENT, 0)))
                .isInstanceOf(CrawlException.class);

        // 第三方共享线路最容易抖：必须是 failed + 游标不动，而不是「试读跳过」
        verify(verdictWriter).writeFailed("b1", ENGINE, VERIFY_A, "第三方 502", "third:recent", null, 1);
        verify(verdictWriter, never()).writeProgress(any(), any(), any(), anyInt(), anyDouble(), any(), anyInt(),
                anyInt(), any(), any());
        verifyNoInteractions(aiDetectClient);
        verifyNoInteractions(faultGuard);
    }

    @Test
    void rejectsConcurrentVerifyForSameBookAndEngine() {
        when(verdictRepository.findByBookIdAndEngineAndModel("b1", ENGINE, VERIFY_A))
                .thenReturn(Optional.empty());
        // 判到一半时另一个标签页又点了一次：应被占位挡下，而不是再判同一章
        java.util.concurrent.atomic.AtomicBoolean reentered = new java.util.concurrent.atomic.AtomicBoolean();
        when(aiDetectClient.detectFree(eq(ENGINE), eq(MODEL_A), anyString())).thenAnswer(inv -> {
            if (reentered.compareAndSet(false, true)) {
                assertThatThrownBy(() -> service.stepVerify("b1", ENGINE, null))
                        .isInstanceOf(OperationConflictException.class)
                        .hasMessageContaining("正在另一处逐章校验");
            }
            return new AiVerdict(12, ENGINE, "不像 AI", null);
        });

        service.stepVerify("b1", ENGINE, null);
        // 上一次结束后占位释放，可以正常继续（异常路径也不会把键永久占住）
        AiDetectService.VerifyStepResult again = service.stepVerify("b1", ENGINE, null);
        assertThat(again.chapterIndex()).isEqualTo(1);
    }

    @Test
    void failedChapterCallLeavesFaultAndLogEvidence() {
        when(verdictRepository.findByBookIdAndEngineAndModel("b1", ENGINE, VERIFY_A))
                .thenReturn(Optional.empty());
        when(aiDetectClient.detectFree(eq(ENGINE), eq(MODEL_A), anyString()))
                .thenThrow(new CrawlException("HTTP 429 额度耗尽", 429, "quota"));

        assertThatThrownBy(() -> service.stepVerify("b1", ENGINE, null))
                .isInstanceOf(CrawlException.class);

        // 失败要留两处证：引擎级故障表（熔断计数依据）+ 判定流水（时间线上「这一章试过」）
        verify(faultGuard).recordFailure(eq(ENGINE), eq(MODEL_A), any(CrawlException.class), eq("b1"));
        verify(verdictWriter).writeFailed(eq("b1"), eq(ENGINE), eq(VERIFY_A), anyString(),
                eq("official:head"), isNull(), eq(1));
    }

    /** 逐章写入的两组口径：明细（命中后保持结论）与流水（本章实际判成什么）。 */
    private static AiVerdictWriter.RoundVerdict round(int flag, double score, String reason) {
        return new AiVerdictWriter.RoundVerdict(flag, score, reason);
    }

    private static NovelAiVerdict row(int flag, double score, String reason, int checked) {
        NovelAiVerdict row = new NovelAiVerdict();
        row.setBookId("b1");
        row.setEngine(ENGINE);
        row.setAiFlag(flag);
        row.setAiScore(score);
        row.setReason(reason);
        row.setChaptersChecked(checked);
        // 游标只在取样策略一致时生效；夹具统一按默认档
        row.setSampleMode("official:head");
        return row;
    }
}
