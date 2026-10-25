package org.example.fqxs.bootstrap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.config.FanqieConfig;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.model.CategoryCrawlResult;
import org.example.fqxs.service.RankService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 每天凌晨 1 点自动重拉全部榜单，等价于页面上的「一键获取」。
 *
 * <p>抓取口径与手动完全一致：只抓展示中的分类、每类抓满整个榜单，
 * 走的也是同一把全量爬取锁——所以撞上手动批量时这次自动拉取直接让路
 * （记 warn 跳过），不排队、不重试，等次日再说。</p>
 *
 * <p>默认开启；要关掉把 {@code app.rank-daily-fetch-enabled} 设成 false 即可
 * （整颗 bean 不装配，调度器里也就没有这个任务）。任务异常全部自兜：
 * 这里只记日志，保证一次失败不会让第二天的任务消失。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.rank-daily-fetch-enabled", havingValue = "true", matchIfMissing = true)
public class DailyRankFetchJob {

    private final RankService rankService;

    /**
     * 每天 01:00 全量拉取一次。cron 六段（秒 分 时 日 月 周），时区固定东八区，
     * 避免服务器用 UTC 时差让「凌晨 1 点」跑到上午 9 点。
     */
    @Scheduled(cron = "${app.rank-daily-fetch-cron:0 0 1 * * *}", zone = "Asia/Shanghai")
    public void fetchAllRanksDaily() {
        try {
            Summary summary = fetchOnce();
            log.info("每日全量拉取完成：{} 本，失败分类 {}，未落库分类 {}，耗时 {} ms",
                    summary.totalBooks(), summary.failed(), summary.notSaved(), summary.millis());
        } catch (CrawlException busy) {
            // 手动「一键获取」正在跑：让路即可，不重试
            log.warn("每日全量拉取让路：{}", busy.getMessage());
        } catch (RuntimeException failure) {
            log.error("每日全量拉取失败（不影响次日任务）：{}", failure.getMessage(), failure);
        }
    }

    /** 执行一次全量拉取并汇总；包级可见供单测直接调用，不经过调度器。 */
    Summary fetchOnce() {
        long start = System.currentTimeMillis();
        List<CategoryCrawlResult> results = rankService.crawlAllCategories(FanqieConfig.wholeListSize(null));

        int totalBooks = results.stream().mapToInt(CategoryCrawlResult::fetched).sum();
        List<String> failed = results.stream()
                .filter(CategoryCrawlResult::failed)
                .map(result -> result.name() + "(" + result.error() + ")")
                .toList();
        // 抓到了却一行没落库：数据库停着时必须留证，不能只看「成功分类数」
        List<String> notSaved = results.stream()
                .filter(result -> !result.failed() && !result.dbSaved())
                .map(CategoryCrawlResult::name)
                .toList();
        return new Summary(totalBooks, failed, notSaved, System.currentTimeMillis() - start);
    }

    /** 一次自动拉取的结果，字段与页面「一键获取」回执同口径。 */
    record Summary(int totalBooks, List<String> failed, List<String> notSaved, long millis) {
    }
}
