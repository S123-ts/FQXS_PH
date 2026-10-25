package org.example.fqxs.bootstrap;

import org.example.fqxs.config.FanqieConfig;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.model.CategoryCrawlResult;
import org.example.fqxs.service.RankService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 每日全量拉取的契约：汇总口径与页面「一键获取」一致，抓的数量是整榜；
 * 锁被手动占用时让路、其它异常也只记日志——两种情况都不能把异常抛给调度器。
 */
class DailyRankFetchJobTest {

    private final RankService rankService = mock(RankService.class);
    private final DailyRankFetchJob job = new DailyRankFetchJob(rankService);

    @Test
    void summarizesFetchedAndFailedCategories() {
        when(rankService.crawlAllCategories(anyInt())).thenReturn(List.of(
                new CategoryCrawlResult("c1", "传统玄幻", 1, 1, 120, true, null),
                new CategoryCrawlResult("c2", "都市高武", 1, 2, 0, false, "上游 502")));

        DailyRankFetchJob.Summary summary = job.fetchOnce();

        assertEquals(120, summary.totalBooks());
        assertEquals(1, summary.failed().size());
        assertTrue(summary.failed().get(0).contains("都市高武"));
        assertEquals(0, summary.notSaved().size(), "失败分类不算未落库");
    }

    /** 抓到了却没落库（数据库不可用）：必须单独统计，不能并进失败分类里。 */
    @Test
    void reportsCategoriesFetchedButNotSaved() {
        when(rankService.crawlAllCategories(anyInt())).thenReturn(List.of(
                new CategoryCrawlResult("c1", "东方仙侠", 1, 3, 200, false, null)));

        DailyRankFetchJob.Summary summary = job.fetchOnce();

        assertEquals(200, summary.totalBooks());
        assertEquals(0, summary.failed().size());
        assertEquals(List.of("东方仙侠"), summary.notSaved());
    }

    /** 抓的是整榜：size 必须等于「不指定每页数量」时的上限，而不是默认页码大小。 */
    @Test
    void fetchesWholeListSize() {
        when(rankService.crawlAllCategories(anyInt())).thenReturn(List.of());

        job.fetchOnce();

        ArgumentCaptor<Integer> size = ArgumentCaptor.forClass(Integer.class);
        verify(rankService).crawlAllCategories(size.capture());
        assertEquals(FanqieConfig.wholeListSize(null), size.getValue());
    }

    /** 手动「一键获取」正在跑：让路，不抛异常、不重试。 */
    @Test
    void busyLockIsSwallowed() {
        when(rankService.crawlAllCategories(anyInt()))
                .thenThrow(new CrawlException("已有全量爬取任务正在执行，请稍后再试"));

        assertDoesNotThrow(job::fetchAllRanksDaily);
    }

    /** 任何意外异常都不能冒到调度器：否则第二天这一次任务就不一定还会被触发。 */
    @Test
    void unexpectedFailureIsSwallowed() {
        when(rankService.crawlAllCategories(anyInt()))
                .thenThrow(new IllegalStateException("数据库不可用"));

        assertDoesNotThrow(job::fetchAllRanksDaily);
    }
}
