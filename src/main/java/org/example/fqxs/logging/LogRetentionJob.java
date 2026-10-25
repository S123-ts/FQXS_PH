package org.example.fqxs.logging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.util.DateUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 日志保留策略：<b>书在日志就在，书没了日志一起删</b>；没有书归属的系统级日志按天数兜底。
 *
 * <p>为什么按书而不是按天数：日志的价值是「这本书出过什么问题」。一本书还在榜上，
 * 三天前它那次失败仍然是有效证据；反过来，书已经掉榜或被删了，它的日志再留多久也没人会查，
 * 只是白占库。所以书级日志不设天数上限，只跟随 {@code novel_rank} 里是否存在该 book_id。</p>
 *
 * <p>系统级日志（启动、定时任务、榜单抓取）没有 book_id，只能按天数清——
 * 它们体量最大且过期最快，{@code app_log} 默认只留 7 天。</p>
 *
 * <p>清理失败只记日志、不重试：第二天还会跑，卡住一次不影响业务。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogRetentionJob {

    /** 书已不在榜：删掉它名下的全部日志（含第三方抽取记录）。 */
    private static final String ORPHAN_APP = """
            DELETE FROM app_log a WHERE a.book_id IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM novel_rank n WHERE n.book_id = a.book_id)
            """;

    private static final String ORPHAN_API = """
            DELETE FROM api_log a WHERE a.book_id IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM novel_rank n WHERE n.book_id = a.book_id)
            """;

    private static final String ORPHAN_THIRD = """
            DELETE FROM third_party_log t WHERE t.book_id IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM novel_rank n WHERE n.book_id = t.book_id)
            """;

    /** 系统级日志（无 book_id）按天数过期。 */
    private static final String EXPIRE_APP = "DELETE FROM app_log WHERE book_id IS NULL AND log_time < ?";

    private static final String EXPIRE_API = "DELETE FROM api_log WHERE book_id IS NULL AND api_time < ?";

    private static final String EXPIRE_THIRD =
            "DELETE FROM third_party_log WHERE book_id IS NULL AND request_time < ?";

    private final JdbcTemplate jdbcTemplate;
    private final LogDbConfig config;

    /** 每天 03:30 清理一次，错开凌晨 01:00 的全量拉榜。 */
    @Scheduled(cron = "${app.log-db.retention-cron:0 30 3 * * *}", zone = "Asia/Shanghai")
    public void prune() {
        if (!config.isEnabled()) {
            return;
        }
        try {
            PruneResult result = pruneOnce();
            log.info("日志清理完成: 书级孤儿 app={} api={} third={}，系统级过期 app={} api={} third={}",
                    result.orphanApp(), result.orphanApi(), result.orphanThird(),
                    result.expiredApp(), result.expiredApi(), result.expiredThird());
        } catch (RuntimeException failure) {
            // 不重试：次日还有一次；这里只留证据
            log.error("日志清理失败（不影响次日任务）: {}", failure.getMessage(), failure);
        }
    }

    /** 执行一次清理；包级可见供单测直接调用，不经过调度器。 */
    PruneResult pruneOnce() {
        LocalDateTime now = DateUtil.now();
        return new PruneResult(
                jdbcTemplate.update(ORPHAN_APP),
                jdbcTemplate.update(ORPHAN_API),
                jdbcTemplate.update(ORPHAN_THIRD),
                jdbcTemplate.update(EXPIRE_APP, now.minusDays(Math.max(1, config.getAppKeepDays()))),
                jdbcTemplate.update(EXPIRE_API, now.minusDays(Math.max(1, config.getApiKeepDays()))),
                jdbcTemplate.update(EXPIRE_THIRD, now.minusDays(Math.max(1, config.getThirdPartyKeepDays()))));
    }

    /** 一次清理删掉的行数，按「书级孤儿」与「系统级过期」分开计数，便于看是哪条规则在起作用。 */
    record PruneResult(int orphanApp, int orphanApi, int orphanThird,
                       int expiredApp, int expiredApi, int expiredThird) {
    }
}
