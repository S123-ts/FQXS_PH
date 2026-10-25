package org.example.fqxs.bootstrap;

import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.service.BookMetaEnricher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 后台补齐线程的硬约束：一轮最多 perHour 本、按热度序推进、每本之间一个节拍、
 * 连续失败 3 本判线路不可用收尾、手动路径占锁就让路（有上限，不空转）、开关关闭不起线程。
 * 节拍在测试里用构造器的 override 压到 0，不碰真实线路、不做真实等待。
 */
@ExtendWith(MockitoExtension.class)
class BookMetaBackfillRunnerTest {

    @Mock
    private BookMetaEnricher enricher;

    private AiDetectConfig config;

    private BookMetaBackfillRunner runner;

    @BeforeEach
    void setUp() {
        config = new AiDetectConfig();
        config.setBookMetaBackfillEnabled(true);
        config.setThirdPartyEnabled(true);
        // 让路重试间隔压到 1ms；节拍由构造器 override 压到 0（生产是 3600000 / perHour）
        config.setBookMetaIntervalMillis(1);
        config.setBookMetaBackfillPerHour(5);
        runner = new BookMetaBackfillRunner(enricher, config, 0);
    }

    @Test
    void emptyPendingTouchesNothing() {
        when(enricher.pendingMetaBookIds()).thenReturn(List.of());

        BookMetaBackfillRunner.RoundStats stats = runner.backfillRound();

        assertEquals(0, stats.attempted());
        assertFalse(stats.lineDown());
        verify(enricher, times(1)).pendingMetaBookIds();
        verify(enricher, times(0)).enrichOne(anyString());
    }

    @Test
    void walksPendingQueueInOrderAndCountsUpdated() {
        when(enricher.pendingMetaBookIds()).thenReturn(List.of("1", "2"));
        when(enricher.enrichOne("1")).thenReturn(BookMetaEnricher.Outcome.UPDATED);
        when(enricher.enrichOne("2")).thenReturn(BookMetaEnricher.Outcome.UPDATED);

        BookMetaBackfillRunner.RoundStats stats = runner.backfillRound();

        assertEquals(2, stats.attempted());
        assertEquals(2, stats.updated());
        assertEquals(0, stats.failed());
        assertEquals(0, stats.yielded());
        assertFalse(stats.lineDown());
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(enricher);
        inOrder.verify(enricher).enrichOne("1");
        inOrder.verify(enricher).enrichOne("2");
    }

    /** 待补很多时，一轮只推进 perHour 本，剩下的留给下一轮。 */
    @Test
    void takesAtMostPerHourBooksPerRound() {
        when(enricher.pendingMetaBookIds()).thenReturn(List.of("1", "2", "3", "4", "5", "6", "7", "8"));
        when(enricher.enrichOne(anyString())).thenReturn(BookMetaEnricher.Outcome.UPDATED);

        BookMetaBackfillRunner.RoundStats stats = runner.backfillRound();

        assertEquals(5, stats.attempted());
        assertEquals(5, stats.updated());
        assertFalse(stats.lineDown());
        verify(enricher, times(5)).enrichOne(anyString());
    }

    /** 按热度序取队首，不打乱顺序：热门书先补到，这是改回线性节拍的主要目的。 */
    @Test
    void takesHeadOfQueueInHeatOrder() {
        when(enricher.pendingMetaBookIds()).thenReturn(List.of("1", "2", "3", "4", "5", "6", "7", "8"));
        when(enricher.enrichOne(anyString())).thenReturn(BookMetaEnricher.Outcome.UPDATED);

        runner.backfillRound();

        org.mockito.ArgumentCaptor<String> ids = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(enricher, times(5)).enrichOne(ids.capture());
        assertEquals(List.of("1", "2", "3", "4", "5"), ids.getAllValues());
    }

    @Test
    void stopsRoundAfterThreeConsecutiveFailures() {
        when(enricher.pendingMetaBookIds()).thenReturn(List.of("1", "2", "3", "4"));
        when(enricher.enrichOne(anyString())).thenReturn(BookMetaEnricher.Outcome.FAILED);

        BookMetaBackfillRunner.RoundStats stats = runner.backfillRound();

        assertEquals(3, stats.attempted());
        assertEquals(0, stats.updated());
        assertEquals(3, stats.failed());
        assertTrue(stats.lineDown());
        verify(enricher, times(3)).enrichOne(anyString());
    }

    @Test
    void yieldsWhenManualEnrichHoldsLockThenRetriesSameBook() {
        when(enricher.pendingMetaBookIds()).thenReturn(List.of("1"));
        when(enricher.enrichOne("1"))
                .thenReturn(BookMetaEnricher.Outcome.LOCKED)
                .thenReturn(BookMetaEnricher.Outcome.UPDATED);

        BookMetaBackfillRunner.RoundStats stats = runner.backfillRound();

        assertEquals(1, stats.attempted());
        assertEquals(1, stats.updated());
        assertEquals(1, stats.yielded());
        assertFalse(stats.lineDown());
        verify(enricher, times(2)).enrichOne("1");
    }

    @Test
    void endsRoundAfterTooManyYields() {
        when(enricher.pendingMetaBookIds()).thenReturn(List.of("1"));
        when(enricher.enrichOne("1")).thenReturn(BookMetaEnricher.Outcome.LOCKED);

        BookMetaBackfillRunner.RoundStats stats = runner.backfillRound();

        assertEquals(BookMetaBackfillRunner.MAX_YIELDS_PER_ROUND + 1, stats.yielded());
        assertEquals(0, stats.attempted());
        assertFalse(stats.lineDown());
        verify(enricher, times(BookMetaBackfillRunner.MAX_YIELDS_PER_ROUND + 1)).enrichOne("1");
    }

    @Test
    void failureStreakResetsAfterSuccess() {
        when(enricher.pendingMetaBookIds()).thenReturn(List.of("1", "2", "3", "4"));
        when(enricher.enrichOne("1")).thenReturn(BookMetaEnricher.Outcome.FAILED);
        when(enricher.enrichOne("2")).thenReturn(BookMetaEnricher.Outcome.UPDATED);
        when(enricher.enrichOne("3")).thenReturn(BookMetaEnricher.Outcome.FAILED);
        when(enricher.enrichOne("4")).thenReturn(BookMetaEnricher.Outcome.UPDATED);

        BookMetaBackfillRunner.RoundStats stats = runner.backfillRound();

        assertEquals(4, stats.attempted());
        assertEquals(2, stats.updated());
        assertEquals(2, stats.failed());
        assertFalse(stats.lineDown());
    }

    /** 节拍配置写得再大也按 360 本/小时钳制：共享线路不能被一次打满。 */
    @Test
    void perHourIsClampedToMax() {
        config.setBookMetaBackfillPerHour(100000);

        assertEquals(BookMetaBackfillRunner.MAX_PER_HOUR, runner.perHour());
    }

    /** 配置非法（0 或负数）时按 1 本/小时兜底，不出现除零。 */
    @Test
    void perHourFallsBackToOneWhenMisconfigured() {
        config.setBookMetaBackfillPerHour(0);

        assertEquals(1, runner.perHour());
    }

    /** 已下架的书不计失败：3 本 SKIPPED 之后照常补后面的书，不会误判线路不可用。 */
    @Test
    void skippedBooksDoNotCountAsLineFailures() {
        when(enricher.pendingMetaBookIds()).thenReturn(List.of("1", "2", "3", "4", "5"));
        when(enricher.enrichOne("1")).thenReturn(BookMetaEnricher.Outcome.SKIPPED);
        when(enricher.enrichOne("2")).thenReturn(BookMetaEnricher.Outcome.SKIPPED);
        when(enricher.enrichOne("3")).thenReturn(BookMetaEnricher.Outcome.SKIPPED);
        when(enricher.enrichOne("4")).thenReturn(BookMetaEnricher.Outcome.FAILED);
        when(enricher.enrichOne("5")).thenReturn(BookMetaEnricher.Outcome.UPDATED);

        BookMetaBackfillRunner.RoundStats stats = runner.backfillRound();

        assertEquals(5, stats.attempted());
        assertEquals(3, stats.skipped());
        assertEquals(1, stats.failed());
        assertEquals(1, stats.updated());
        assertFalse(stats.lineDown());
        assertFalse(stats.coolingDown());
    }

    /** 线路冷却中：本轮立即收尾、交给 loop 等一个节拍，既不记失败也不继续撞。 */
    @Test
    void coolingDownEndsRoundImmediately() {
        when(enricher.pendingMetaBookIds()).thenReturn(List.of("1", "2", "3", "4", "5"));
        when(enricher.enrichOne(anyString())).thenReturn(BookMetaEnricher.Outcome.COOLDOWN);

        BookMetaBackfillRunner.RoundStats stats = runner.backfillRound();

        assertEquals(0, stats.attempted());
        assertTrue(stats.coolingDown());
        assertFalse(stats.lineDown());
        verify(enricher, times(1)).enrichOne(anyString());
    }

    /** 一轮推进的本数等于 perHour，队首之外的待补留给下一轮（用 20 本队验证 5 本/轮的边界）。 */
    @Test
    void leavesRestOfQueueForNextRound() {
        List<String> pending = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            pending.add(String.valueOf(i));
        }
        when(enricher.pendingMetaBookIds()).thenReturn(pending);
        when(enricher.enrichOne(anyString())).thenReturn(BookMetaEnricher.Outcome.UPDATED);

        BookMetaBackfillRunner.RoundStats stats = runner.backfillRound();

        assertEquals(5, stats.attempted());
        org.mockito.ArgumentCaptor<String> ids = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(enricher, times(5)).enrichOne(ids.capture());
        assertEquals(List.of("0", "1", "2", "3", "4"), ids.getAllValues());
    }

    @Test
    void runDoesNothingWhenBackfillDisabled() {
        config.setBookMetaBackfillEnabled(false);

        runner.run(null);

        verifyNoInteractions(enricher);
    }

    @Test
    void runDoesNothingWhenThirdPartyDisabled() {
        config.setThirdPartyEnabled(false);

        runner.run(null);

        verifyNoInteractions(enricher);
    }
}
