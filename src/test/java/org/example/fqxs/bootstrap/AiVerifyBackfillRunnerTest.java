package org.example.fqxs.bootstrap;

import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.exception.LineCoolingException;
import org.example.fqxs.exception.OperationConflictException;
import org.example.fqxs.exception.QuotaExceededException;
import org.example.fqxs.model.SampleSpec;
import org.example.fqxs.repository.NovelRepository;
import org.example.fqxs.service.AiDetectService;
import org.example.fqxs.service.AiEngineRegistry;
import org.example.fqxs.service.AiVerifyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 后台逐章校验线程的硬约束：一轮里每个可用引擎各推进一章、只在家族代表型号上跑、
 * 候选按代表型号判「查完」、手动校验占着就让路、额度到顶与线路冷却都本轮收尾、
 * 连续失败 3 次判线路不可用、开关关闭不起线程。
 * 节拍在测试里用构造器的 override 压到 0，不碰真实线路、不做真实等待。
 */
@ExtendWith(MockitoExtension.class)
class AiVerifyBackfillRunnerTest {

    @Mock
    private AiVerifyService verifyService;

    @Mock
    private AiEngineRegistry engineRegistry;

    @Mock
    private NovelRepository novelRepository;

    private AiDetectConfig config;

    private AiVerifyBackfillRunner runner;

    @BeforeEach
    void setUp() {
        config = new AiDetectConfig();
        config.setAiVerifyBackfillEnabled(true);
        config.setEnabled(true);
        config.setAiVerifyBackfillPerHour(6);
        runner = new AiVerifyBackfillRunner(verifyService, engineRegistry, novelRepository, config, 0);
    }

    private static Map<String, String> engine(String id) {
        return Map.of("id", id, "name", id, "shortName", id);
    }

    private static AiDetectService.VerifyStepResult step(String bookId, String engine) {
        return new AiDetectService.VerifyStepResult(bookId, engine, "glm-4-flash#verify",
                1, 10, 1, "第 1 章", 12.0, false, false, false, false, "ok");
    }

    @Test
    void noAvailableEngineSkipsRound() {
        when(engineRegistry.availableVerifyEngines()).thenReturn(List.of());

        AiVerifyBackfillRunner.RoundStats stats = runner.verifyRound();

        assertEquals(0, stats.attempted());
        assertFalse(stats.idle());
        verifyNoInteractions(novelRepository, verifyService);
    }

    /** 一轮里每个引擎各推进一章：三家引擎 = 三次模型调用，这就是「10 分钟三次一本」的账。 */
    @Test
    void stepsOneChapterPerAvailableEngine() {
        when(engineRegistry.availableVerifyEngines())
                .thenReturn(List.of(engine("glm-4-flash"), engine("qianfan"), engine("qwen")));
        when(engineRegistry.distinctModelsOf(anyString())).thenReturn(List.of("m1"));
        when(novelRepository.findBookIdsNeedingVerify(anyString(), ArgumentMatchers.<String>anyCollection(), anyInt(), anyInt(),
                any(Pageable.class))).thenReturn(List.of("b1"));
        when(verifyService.stepVerify(anyString(), anyString(), any(SampleSpec.class), ArgumentMatchers.<String>anyList()))
                .thenAnswer(call -> step(call.getArgument(0), call.getArgument(1)));

        AiVerifyBackfillRunner.RoundStats stats = runner.verifyRound();

        assertEquals(3, stats.attempted());
        assertEquals(3, stats.stepped());
        assertEquals(0, stats.failed());
        assertFalse(stats.lineDown());
        verify(verifyService).stepVerify("b1", "glm-4-flash", SampleSpec.defaultSpec(), List.of("m1"));
        verify(verifyService).stepVerify("b1", "qianfan", SampleSpec.defaultSpec(), List.of("m1"));
        verify(verifyService).stepVerify("b1", "qwen", SampleSpec.defaultSpec(), List.of("m1"));
    }

    /** 传给 stepVerify 的是<b>家族代表</b>而非全池：同模型的包装变体不再各烧一遍额度。 */
    @Test
    void passesFamilyRepresentativesNotFullPool() {
        when(engineRegistry.availableVerifyEngines()).thenReturn(List.of(engine("qianfan")));
        when(engineRegistry.distinctModelsOf("qianfan"))
                .thenReturn(List.of("ernie-4.5-turbo-32k", "GLM-4-9B-0414"));
        when(novelRepository.findBookIdsNeedingVerify(anyString(), ArgumentMatchers.<String>anyCollection(), anyInt(), anyInt(),
                any(Pageable.class))).thenReturn(List.of("b1"));
        when(verifyService.stepVerify(anyString(), anyString(), any(SampleSpec.class), ArgumentMatchers.<String>anyList()))
                .thenReturn(step("b1", "qianfan"));

        runner.verifyRound();

        verify(verifyService).stepVerify("b1", "qianfan", SampleSpec.defaultSpec(),
                List.of("ernie-4.5-turbo-32k", "GLM-4-9B-0414"));
    }

    /** 候选查询带上代表型号的行标识（带 #verify 后缀）与章数：被去重掉的型号的历史行不算「已查完」。 */
    @Test
    void candidateQueryCarriesRepresentativeRowModels() {
        when(engineRegistry.availableVerifyEngines()).thenReturn(List.of(engine("glm-4-flash")));
        when(engineRegistry.distinctModelsOf("glm-4-flash")).thenReturn(List.of("glm-5.3-flash"));
        when(novelRepository.findBookIdsNeedingVerify(anyString(), ArgumentMatchers.<String>anyCollection(), anyInt(), anyInt(),
                any(Pageable.class))).thenReturn(List.of("b1"));
        when(verifyService.stepVerify(anyString(), anyString(), any(SampleSpec.class), ArgumentMatchers.<String>anyList()))
                .thenReturn(step("b1", "glm-4-flash"));

        runner.verifyRound();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> rowModels = ArgumentCaptor.forClass(Collection.class);
        verify(novelRepository).findBookIdsNeedingVerify(eq("glm-4-flash"), rowModels.capture(),
                eq(1), eq(SampleSpec.HEAD_CHAPTERS), any(Pageable.class));
        assertEquals(List.of("glm-5.3-flash#verify"), List.copyOf(rowModels.getValue()));
    }

    /** 只取队首一本：一轮 10 分钟，排队的书靠下一轮进来，不在一轮里连着打上游。 */
    @Test
    void takesOnlyHeadOfCandidateQueue() {
        when(engineRegistry.availableVerifyEngines()).thenReturn(List.of(engine("glm-4-flash")));
        when(engineRegistry.distinctModelsOf(anyString())).thenReturn(List.of("m1"));
        when(novelRepository.findBookIdsNeedingVerify(anyString(), ArgumentMatchers.<String>anyCollection(), anyInt(), anyInt(),
                any(Pageable.class))).thenReturn(List.of("b1", "b2", "b3"));
        when(verifyService.stepVerify(anyString(), anyString(), any(SampleSpec.class), ArgumentMatchers.<String>anyList()))
                .thenReturn(step("b1", "glm-4-flash"));

        AiVerifyBackfillRunner.RoundStats stats = runner.verifyRound();

        assertEquals(1, stats.attempted());
        verify(verifyService, times(1)).stepVerify(eq("b1"), anyString(), any(SampleSpec.class),
                ArgumentMatchers.<String>anyList());
    }

    /** 候选空了不算失败：该引擎把在库的书查完，整轮无事可做时拉长到每小时复查。 */
    @Test
    void emptyCandidatesCountAsDoneEngineAndIdle() {
        when(engineRegistry.availableVerifyEngines()).thenReturn(List.of(engine("glm-4-flash")));
        when(engineRegistry.distinctModelsOf(anyString())).thenReturn(List.of("m1"));
        when(novelRepository.findBookIdsNeedingVerify(anyString(), ArgumentMatchers.<String>anyCollection(), anyInt(), anyInt(),
                any(Pageable.class))).thenReturn(List.of());

        AiVerifyBackfillRunner.RoundStats stats = runner.verifyRound();

        assertEquals(0, stats.attempted());
        assertEquals(1, stats.doneEngines());
        assertTrue(stats.idle());
        assertFalse(stats.lineDown());
        verifyNoInteractions(verifyService);
    }

    /** 页面正在手动校验同一本同一引擎：让路，不记失败，也不影响本轮其余引擎。 */
    @Test
    void manualVerifyConflictYieldsWithoutFailure() {
        when(engineRegistry.availableVerifyEngines())
                .thenReturn(List.of(engine("glm-4-flash"), engine("qianfan")));
        when(engineRegistry.distinctModelsOf(anyString())).thenReturn(List.of("m1"));
        when(novelRepository.findBookIdsNeedingVerify(anyString(), ArgumentMatchers.<String>anyCollection(), anyInt(), anyInt(),
                any(Pageable.class))).thenReturn(List.of("b1"));
        when(verifyService.stepVerify("b1", "glm-4-flash", SampleSpec.defaultSpec(), List.of("m1")))
                .thenThrow(new OperationConflictException("正在另一处逐章校验"));
        when(verifyService.stepVerify("b1", "qianfan", SampleSpec.defaultSpec(), List.of("m1")))
                .thenReturn(step("b1", "qianfan"));

        AiVerifyBackfillRunner.RoundStats stats = runner.verifyRound();

        assertEquals(1, stats.yielded());
        assertEquals(1, stats.stepped());
        assertEquals(0, stats.failed());
        assertFalse(stats.lineDown());
    }

    /** 额度到顶：本轮立即收尾，后面的引擎不再打，且不算失败（等一小时额度刷新）。 */
    @Test
    void quotaExhaustedEndsRoundImmediately() {
        when(engineRegistry.availableVerifyEngines())
                .thenReturn(List.of(engine("glm-4-flash"), engine("qianfan")));
        when(engineRegistry.distinctModelsOf(anyString())).thenReturn(List.of("m1"));
        when(novelRepository.findBookIdsNeedingVerify(anyString(), ArgumentMatchers.<String>anyCollection(), anyInt(), anyInt(),
                any(Pageable.class))).thenReturn(List.of("b1"));
        when(verifyService.stepVerify("b1", "glm-4-flash", SampleSpec.defaultSpec(), List.of("m1")))
                .thenThrow(new QuotaExceededException("今日额度已用尽"));

        AiVerifyBackfillRunner.RoundStats stats = runner.verifyRound();

        assertTrue(stats.quotaExhausted());
        assertEquals(0, stats.failed());
        assertFalse(stats.lineDown());
        verify(verifyService, times(1)).stepVerify(anyString(), anyString(), any(SampleSpec.class),
                ArgumentMatchers.<String>anyList());
    }

    /** 上游线路冷却：本轮收尾，交给 loop 等一个节拍，绝不记成故障。 */
    @Test
    void lineCoolingEndsRoundWithoutFailure() {
        when(engineRegistry.availableVerifyEngines()).thenReturn(List.of(engine("glm-4-flash")));
        when(engineRegistry.distinctModelsOf(anyString())).thenReturn(List.of("m1"));
        when(novelRepository.findBookIdsNeedingVerify(anyString(), ArgumentMatchers.<String>anyCollection(), anyInt(), anyInt(),
                any(Pageable.class))).thenReturn(List.of("b1"));
        when(verifyService.stepVerify(anyString(), anyString(), any(SampleSpec.class), ArgumentMatchers.<String>anyList()))
                .thenThrow(new LineCoolingException("线路冷却中"));

        AiVerifyBackfillRunner.RoundStats stats = runner.verifyRound();

        assertTrue(stats.coolingDown());
        assertEquals(0, stats.failed());
        assertFalse(stats.lineDown());
    }

    /** 连续失败 3 次收尾（引擎级的一小时停用由熔断器负责，这里只管本轮冷静期）。 */
    @Test
    void threeConsecutiveFailuresEndRound() {
        when(engineRegistry.availableVerifyEngines())
                .thenReturn(List.of(engine("e1"), engine("e2"), engine("e3"), engine("e4")));
        when(engineRegistry.distinctModelsOf(anyString())).thenReturn(List.of("m1"));
        when(novelRepository.findBookIdsNeedingVerify(anyString(), ArgumentMatchers.<String>anyCollection(), anyInt(), anyInt(),
                any(Pageable.class))).thenReturn(List.of("b1"));
        when(verifyService.stepVerify(anyString(), anyString(), any(SampleSpec.class), ArgumentMatchers.<String>anyList()))
                .thenThrow(new IllegalStateException("上游 500"));

        AiVerifyBackfillRunner.RoundStats stats = runner.verifyRound();

        assertEquals(3, stats.failed());
        assertTrue(stats.lineDown());
        verify(verifyService, times(3)).stepVerify(anyString(), anyString(), any(SampleSpec.class),
                ArgumentMatchers.<String>anyList());
    }

    /** 成功一次就清零连续失败计数：零星抖动不该把整轮掐断。 */
    @Test
    void failureStreakResetsAfterSuccess() {
        when(engineRegistry.availableVerifyEngines())
                .thenReturn(List.of(engine("e1"), engine("e2"), engine("e3"), engine("e4")));
        when(engineRegistry.distinctModelsOf(anyString())).thenReturn(List.of("m1"));
        when(novelRepository.findBookIdsNeedingVerify(anyString(), ArgumentMatchers.<String>anyCollection(), anyInt(), anyInt(),
                any(Pageable.class))).thenReturn(List.of("b1"));
        when(verifyService.stepVerify("b1", "e1", SampleSpec.defaultSpec(), List.of("m1")))
                .thenThrow(new IllegalStateException("上游 500"));
        when(verifyService.stepVerify("b1", "e2", SampleSpec.defaultSpec(), List.of("m1")))
                .thenThrow(new IllegalStateException("上游 500"));
        when(verifyService.stepVerify("b1", "e3", SampleSpec.defaultSpec(), List.of("m1")))
                .thenReturn(step("b1", "e3"));
        when(verifyService.stepVerify("b1", "e4", SampleSpec.defaultSpec(), List.of("m1")))
                .thenThrow(new IllegalStateException("上游 500"));

        AiVerifyBackfillRunner.RoundStats stats = runner.verifyRound();

        assertEquals(4, stats.attempted());
        assertEquals(1, stats.stepped());
        assertEquals(3, stats.failed());
        assertFalse(stats.lineDown());
    }

    /** 节拍配置写得再大也按 60 轮/小时钳制：再快免费额度当天就见底。 */
    @Test
    void perHourIsClampedToMax() {
        config.setAiVerifyBackfillPerHour(100000);

        assertEquals(AiVerifyBackfillRunner.MAX_PER_HOUR, runner.perHour());
    }

    /** 配置非法（0 或负数）时按 1 轮/小时兜底，不出现除零。 */
    @Test
    void perHourFallsBackToOneWhenMisconfigured() {
        config.setAiVerifyBackfillPerHour(0);

        assertEquals(1, runner.perHour());
    }

    @Test
    void perHourDefaultIsTenMinuteBeat() {
        assertEquals(6, new AiVerifyBackfillRunner(verifyService, engineRegistry, novelRepository,
                new AiDetectConfig(), 0).perHour());
    }

    @Test
    void runDoesNothingWhenBackfillDisabled() {
        config.setAiVerifyBackfillEnabled(false);

        runner.run(null);

        verifyNoInteractions(engineRegistry, novelRepository, verifyService);
    }

    @Test
    void runDoesNothingWhenAiDetectDisabled() {
        config.setEnabled(false);

        runner.run(null);

        verifyNoInteractions(engineRegistry, novelRepository, verifyService);
    }
}
