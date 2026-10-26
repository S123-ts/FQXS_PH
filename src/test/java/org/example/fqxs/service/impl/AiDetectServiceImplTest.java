package org.example.fqxs.service.impl;

import org.example.fqxs.client.AiDetectClient;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.entity.AiEngine;
import org.example.fqxs.entity.NovelAiVerdict;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.model.AiVerdict;
import org.example.fqxs.model.ChapterSample;
import org.example.fqxs.model.SampleSpec;
import org.example.fqxs.repository.NovelAiVerdictRepository;
import org.example.fqxs.repository.NovelRepository;
import org.example.fqxs.service.AiDetectService;
import org.example.fqxs.service.AiEngineRegistry;
import org.example.fqxs.service.AiEngineRouter;
import org.example.fqxs.service.AiSampleCollector;
import org.example.fqxs.service.AiVerdictWriter;
import org.example.fqxs.service.AiVerifyService;
import org.example.fqxs.service.AiDetectStatusService;
import org.example.fqxs.service.AiFaultGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 判定编排的契约（拆分后）：引擎/型号选择、幂等守卫、阈值映射、批量取书与失败隔离。
 * 取样细节见 {@code AiSampleCollectorTest}，逐章见 {@code AiVerifyServiceTest}。
 */
@ExtendWith(MockitoExtension.class)
class AiDetectServiceImplTest {

    private static final String GLM = AiDetectConfig.ENGINE_GLM;
    private static final String TEST_MODEL = "test-model";

    @Mock
    private NovelRepository novelRepository;

    @Mock
    private NovelAiVerdictRepository verdictRepository;

    @Mock
    private AiDetectClient aiDetectClient;

    @Mock
    private AiEngineRegistry engineRegistry;

    @Mock
    private AiEngineRouter engineRouter;

    @Mock
    private AiSampleCollector sampleCollector;

    @Mock
    private AiVerdictWriter verdictWriter;

    @Mock
    private AiVerifyService verifyService;

    @Mock
    private AiDetectStatusService statusService;

    @Mock
    private AiFaultGuard faultGuard;

    private AiDetectConfig aiDetectConfig;

    private AiDetectServiceImpl aiDetectService;

    @BeforeEach
    void setUp() {
        aiDetectConfig = new AiDetectConfig();
        aiDetectConfig.setRequestIntervalMillis(0);
        aiDetectService = new AiDetectServiceImpl(novelRepository, verdictRepository, aiDetectClient,
                aiDetectConfig, engineRegistry, engineRouter, sampleCollector, verdictWriter,
                verifyService, statusService, faultGuard);
        lenient().when(engineRegistry.find(anyString())).thenAnswer(inv -> Optional.of(engine(inv.getArgument(0))));
        lenient().when(engineRegistry.require(anyString())).thenAnswer(inv -> engine(inv.getArgument(0)));
        lenient().when(engineRegistry.hasKey(anyString())).thenReturn(true);
        lenient().when(engineRegistry.modelsOf(anyString())).thenReturn(List.of(TEST_MODEL));
        lenient().when(engineRegistry.pickModel(anyString())).thenReturn(TEST_MODEL);
        lenient().when(engineRouter.resolveAvoiding(anyString(), anyList())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(sampleCollector.collect(anyString(), any())).thenReturn(
                new AiSampleCollector.CollectedSample("正文正文", "free:mid_recent", "1-2", 2));
    }

    /** 引擎夹具：统一用 OpenAI 兼容通道（朱雀通道已移除）。 */
    private static AiEngine engine(String id) {
        return AiEngine.builder()
                .id(id)
                .name(id)
                .baseUrl("https://example.test/v1/chat/completions")
                .model(TEST_MODEL)
                .protocol("openai_chat")
                .builtin(true).enabled(true).autoBan(true).priority(0)
                .build();
    }

    @Test
    void highProbabilityMarksBookAsAi() {
        when(aiDetectClient.detectFree(eq(GLM), eq(TEST_MODEL), any()))
                .thenReturn(new AiVerdict(82.44, GLM, "段落整齐"));

        AiDetectService.FreeDetectResult result = aiDetectService.detectFree("b1", GLM, false);

        assertThat(result.aiFlag()).isEqualTo(2);
        assertThat(result.aiScore()).isEqualTo(82.44);
        verify(verdictWriter).writeSweep("b1", GLM, TEST_MODEL, 2, 82.44, "段落整齐", null,
                "free:mid_recent", "1-2", "sweep", 2);
    }

    @Test
    void thresholdIsInclusive() {
        when(aiDetectClient.detectFree(eq(GLM), eq(TEST_MODEL), any())).thenReturn(new AiVerdict(70, GLM, ""));

        assertThat(aiDetectService.detectFree("b1", GLM, false).aiFlag()).isEqualTo(2);
    }

    @Test
    void middleProbabilityMarksBookAsSuspect() {
        when(aiDetectClient.detectFree(eq(GLM), eq(TEST_MODEL), any())).thenReturn(new AiVerdict(55, GLM, ""));

        assertThat(aiDetectService.detectFree("b1", GLM, false).aiFlag()).isEqualTo(3);
    }

    @Test
    void lowProbabilityMarksBookAsHuman() {
        when(aiDetectClient.detectFree(eq(GLM), eq(TEST_MODEL), any())).thenReturn(new AiVerdict(30, GLM, ""));

        assertThat(aiDetectService.detectFree("b1", GLM, false).aiFlag()).isEqualTo(1);
    }

    @Test
    void missingKeyFailsBeforeSampling() {
        // Key 检查已收敛到 AiEngineRouter：这里模拟 router 因无可用引擎而拒绝
        when(engineRouter.resolveAvoiding(eq(GLM), anyList()))
                .thenThrow(new IllegalArgumentException("没有可用的免费引擎：请先在「API 密钥」面板配置 Key"));

        assertThatThrownBy(() -> aiDetectService.detectFree("b1", GLM, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("API 密钥");
        verifyNoInteractions(sampleCollector);
    }

    @Test
    void idempotentGuardReusesExistingSweepVerdict() {
        NovelAiVerdict cachedRow = verdictRow("b1", GLM, 2, 82.44, "段落整齐",
                LocalDateTime.of(2026, 10, 21, 12, 30, 0));
        when(verdictRepository.findByBookIdAndEngineAndModelIn("b1", GLM, List.of(TEST_MODEL)))
                .thenReturn(List.of(cachedRow));

        AiDetectService.FreeDetectResult result = aiDetectService.detectFree("b1", GLM, false);

        assertThat(result.cached()).isTrue();
        assertThat(result.aiFlag()).isEqualTo(2);
        assertThat(result.aiScore()).isEqualTo(82.44);
        verify(aiDetectClient, never()).detectFree(any(), anyString(), any());
        verifyNoInteractions(sampleCollector);
    }

    @Test
    void idempotentGuardReusesTheLatestVerdictWhenSeveralModelsJudged() {
        NovelAiVerdict older = verdictRow("b1", GLM, 1, 40.0, "旧型号",
                LocalDateTime.of(2026, 10, 20, 10, 0, 0));
        NovelAiVerdict newer = verdictRow("b1", GLM, 2, 88.0, "新型号",
                LocalDateTime.of(2026, 10, 21, 10, 0, 0));
        when(verdictRepository.findByBookIdAndEngineAndModelIn("b1", GLM, List.of(TEST_MODEL)))
                .thenReturn(List.of(older, newer));

        AiDetectService.FreeDetectResult result = aiDetectService.detectFree("b1", GLM, false);

        assertThat(result.cached()).isTrue();
        assertThat(result.aiFlag()).isEqualTo(2);
        assertThat(result.aiScore()).isEqualTo(88.0);
    }

    @Test
    void forceIgnoresCachedVerdictAndRejudges() {
        when(aiDetectClient.detectFree(eq(GLM), eq(TEST_MODEL), any())).thenReturn(new AiVerdict(75, GLM, ""));

        AiDetectService.FreeDetectResult result = aiDetectService.detectFree("b1", GLM, true);

        assertThat(result.cached()).isFalse();
        assertThat(result.aiFlag()).isEqualTo(2);
        verify(aiDetectClient).detectFree(eq(GLM), eq(TEST_MODEL), any());
    }

    @Test
    void verifyProgressRowIsNotReusedAsSweepVerdict() {
        NovelAiVerdict progressRow = verdictRow("b1", GLM, 2, 88.0, "", null);
        progressRow.setChaptersChecked(3);
        when(verdictRepository.findByBookIdAndEngineAndModelIn("b1", GLM, List.of(TEST_MODEL)))
                .thenReturn(List.of(progressRow));
        when(aiDetectClient.detectFree(eq(GLM), eq(TEST_MODEL), any())).thenReturn(new AiVerdict(20, GLM, ""));

        AiDetectService.FreeDetectResult result = aiDetectService.detectFree("b1", GLM, false);

        assertThat(result.cached()).isFalse();
        assertThat(result.aiFlag()).isEqualTo(1);
        verify(sampleCollector).collect(eq("b1"), any());
    }

    @Test
    void sweepIsolatesSingleBookFailure() {
        when(novelRepository.findBookIdsMissingEngineVerdict(eq(GLM), eq(List.of(TEST_MODEL)), any(Pageable.class)))
                .thenReturn(List.of("b1", "b2"));
        when(novelRepository.countBookIdsMissingEngineVerdict(GLM, List.of(TEST_MODEL))).thenReturn(5L);
        when(aiDetectClient.detectFree(eq(GLM), eq(TEST_MODEL), any()))
                .thenThrow(new CrawlException("目录为空，可能被风控"))
                .thenReturn(new AiVerdict(75, GLM, ""));

        AiDetectService.SweepResult result = aiDetectService.sweepFree(10, GLM);

        assertThat(result.processed()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.aiCount()).isEqualTo(1);
        assertThat(result.remaining()).isEqualTo(5);
        assertThat(result.errors()).hasSize(1);
    }

    @Test
    void failedModelAttemptLeavesFailedLogRow() {
        // 型号池只有一个型号，且它失败：这一轮必须留下 kind=failed 的流水，
        // 否则时间线上只剩成功的那几次，「这轮试过但没出结论」看不见
        when(aiDetectClient.detectFree(eq(GLM), eq(TEST_MODEL), any()))
                .thenThrow(new CrawlException("HTTP 429 额度耗尽", 429, "quota"));

        assertThatThrownBy(() -> aiDetectService.detectFree("b1", GLM, false))
                .isInstanceOf(CrawlException.class);

        verify(verdictWriter).writeFailed("b1", GLM, TEST_MODEL, "HTTP 429 额度耗尽",
                "free:mid_recent", "1-2", 0);
        // 失败不写结论：初筛落库（11 参数重载）一次都不该发生
        verify(verdictWriter, never()).writeSweep(anyString(), anyString(), anyString(), anyInt(),
                anyDouble(), any(), any(), any(), any(), anyString(), anyInt());
    }

    @Test
    void sampleFailureAlsoLeavesFailedLogRow() {
        // 取样就失败（第三方通道不可用 / 正文不足）：还没进型号循环，也算一次审核尝试
        when(sampleCollector.collect(anyString(), any()))
                .thenThrow(new CrawlException("会员正文通道暂不可用"));

        assertThatThrownBy(() -> aiDetectService.detectFree("b1", GLM, false))
                .isInstanceOf(CrawlException.class);

        // 取样失败时没有档位与跨度可记，章号也没有单章概念
        verify(verdictWriter).writeFailed("b1", GLM, TEST_MODEL, "会员正文通道暂不可用", null, null, 0);
        verifyNoInteractions(aiDetectClient);
    }

    @Test
    void sweepPicksRandomPageAcrossPendingSet() {
        when(novelRepository.countBookIdsMissingEngineVerdict(GLM, List.of(TEST_MODEL))).thenReturn(100L);
        when(novelRepository.findBookIdsMissingEngineVerdict(eq(GLM), eq(List.of(TEST_MODEL)), any(Pageable.class)))
                .thenReturn(List.of());

        for (int i = 0; i < 40; i++) {
            aiDetectService.sweepFree(10, GLM);
        }

        org.mockito.ArgumentCaptor<Pageable> pageCaptor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(novelRepository, atLeast(40))
                .findBookIdsMissingEngineVerdict(eq(GLM), eq(List.of(TEST_MODEL)), pageCaptor.capture());
        Set<Integer> pages = new HashSet<>();
        for (Pageable pageable : pageCaptor.getAllValues()) {
            pages.add(pageable.getPageNumber());
        }
        assertThat(pages).anyMatch(page -> page > 0);
        assertThat(pages).allMatch(page -> page >= 0 && page < 10);
    }

    @Test
    void sweepWithNothingPendingReturnsImmediately() {
        when(novelRepository.countBookIdsMissingEngineVerdict(GLM, List.of(TEST_MODEL))).thenReturn(0L);

        AiDetectService.SweepResult result = aiDetectService.sweepFree(10, GLM);

        assertThat(result.processed()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.remaining()).isZero();
        verify(novelRepository, never())
                .findBookIdsMissingEngineVerdict(eq(GLM), eq(List.of(TEST_MODEL)), any(Pageable.class));
        verifyNoInteractions(sampleCollector);
    }

    @Test
    void stepVerifyAndStatusAreDelegated() {
        AiDetectService.VerifyStepResult step = new AiDetectService.VerifyStepResult("b1", GLM, TEST_MODEL,
                1, 10, 1, "", 50, false, false, false, false, "ok");
        when(verifyService.stepVerify(eq("b1"), eq(GLM), any())).thenReturn(step);
        AiDetectService.StatusView statusView = new AiDetectService.StatusView(true, List.of(GLM),
                java.util.Map.of(), 0, 0, List.of(), 10, java.util.Map.of(), List.of(GLM));
        when(statusService.status()).thenReturn(statusView);

        assertThat(aiDetectService.stepVerify("b1", GLM, null)).isSameAs(step);
        assertThat(aiDetectService.status()).isSameAs(statusView);
    }

    /** 明细表一行结论。 */
    private NovelAiVerdict verdictRow(String bookId, String engine, int flag, double score, String reason,
                                      LocalDateTime checkedTime) {
        NovelAiVerdict row = new NovelAiVerdict();
        row.setBookId(bookId);
        row.setEngine(engine);
        row.setModel(TEST_MODEL);
        row.setAiFlag(flag);
        row.setAiScore(score);
        row.setReason(reason);
        row.setChaptersChecked(0);
        row.setCheckedTime(checkedTime);
        return row;
    }
}
