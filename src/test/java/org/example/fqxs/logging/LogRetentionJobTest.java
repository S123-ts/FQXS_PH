package org.example.fqxs.logging;

import org.example.fqxs.util.DateUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 保留策略的契约：<b>书在日志就在，书没了日志一起删</b>；没有 book_id 的系统级日志按天数过期。
 *
 * <p>用真实 H2 内存库而不是 mock：这段逻辑全在 SQL 里（相关子查询 + 时间比较），
 * mock 掉 JdbcTemplate 只能证明「调了 6 次 update」，证明不了删对了行。</p>
 */
class LogRetentionJobTest {

    private JdbcTemplate jdbc;

    private LogDbConfig config;

    private LogRetentionJob job;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource("jdbc:h2:mem:log-retention;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE novel_rank (book_id VARCHAR(50))");
        jdbc.execute("CREATE TABLE app_log (log_time TIMESTAMP NOT NULL, book_id VARCHAR(50))");
        jdbc.execute("CREATE TABLE api_log (api_time TIMESTAMP NOT NULL, book_id VARCHAR(50))");
        jdbc.execute("CREATE TABLE third_party_log (request_time TIMESTAMP NOT NULL, book_id VARCHAR(50))");
        config = new LogDbConfig();
        job = new LogRetentionJob(jdbc, config);
    }

    @Test
    void keepsLogsOfBooksStillOnRankAndDeletesOrphans() {
        jdbc.update("INSERT INTO novel_rank VALUES (?)", "on-rank");
        jdbc.update("INSERT INTO app_log VALUES (?, ?)", DateUtil.now(), "on-rank");
        jdbc.update("INSERT INTO app_log VALUES (?, ?)", DateUtil.now(), "gone");
        jdbc.update("INSERT INTO api_log VALUES (?, ?)", DateUtil.now(), "on-rank");
        jdbc.update("INSERT INTO api_log VALUES (?, ?)", DateUtil.now(), "gone");
        jdbc.update("INSERT INTO third_party_log VALUES (?, ?)", DateUtil.now(), "on-rank");
        jdbc.update("INSERT INTO third_party_log VALUES (?, ?)", DateUtil.now(), "gone");

        LogRetentionJob.PruneResult result = job.pruneOnce();

        assertEquals(1, result.orphanApp());
        assertEquals(1, result.orphanApi());
        assertEquals(1, result.orphanThird());
        assertEquals("on-rank", onlyBookId("app_log"));
        assertEquals("on-rank", onlyBookId("api_log"));
        assertEquals("on-rank", onlyBookId("third_party_log"));
    }

    /** 书级日志不按天数过期：只要书还在，半年前的日志也留着。 */
    @Test
    void bookLogsAreNeverExpiredByAge() {
        jdbc.update("INSERT INTO novel_rank VALUES (?)", "on-rank");
        jdbc.update("INSERT INTO app_log VALUES (?, ?)", DateUtil.now().minusDays(365), "on-rank");

        job.pruneOnce();

        assertEquals(1, count("app_log"));
    }

    @Test
    void systemLogsExpireAfterKeepDays() {
        config.setAppKeepDays(7);
        jdbc.update("INSERT INTO app_log VALUES (?, ?)", DateUtil.now().minusDays(8), null);
        jdbc.update("INSERT INTO app_log VALUES (?, ?)", DateUtil.now().minusDays(1), null);

        LogRetentionJob.PruneResult result = job.pruneOnce();

        assertEquals(1, result.expiredApp());
        assertEquals(1, count("app_log"));
    }

    @Test
    void apiAndThirdPartyLogsUseTheirOwnKeepDays() {
        config.setApiKeepDays(30);
        config.setThirdPartyKeepDays(30);
        jdbc.update("INSERT INTO api_log VALUES (?, ?)", DateUtil.now().minusDays(31), null);
        jdbc.update("INSERT INTO third_party_log VALUES (?, ?)", DateUtil.now().minusDays(31), null);
        jdbc.update("INSERT INTO api_log VALUES (?, ?)", DateUtil.now().minusDays(29), null);
        jdbc.update("INSERT INTO third_party_log VALUES (?, ?)", DateUtil.now().minusDays(29), null);

        LogRetentionJob.PruneResult result = job.pruneOnce();

        assertEquals(1, result.expiredApi());
        assertEquals(1, result.expiredThird());
        assertEquals(1, count("api_log"));
        assertEquals(1, count("third_party_log"));
    }

    /** 非法配置（0 或负数）不能把整表删空：至少保留 1 天。 */
    @Test
    void nonPositiveKeepDaysFallsBackToOneDay() {
        config.setAppKeepDays(0);
        jdbc.update("INSERT INTO app_log VALUES (?, ?)", DateUtil.now().minusHours(2), null);

        job.pruneOnce();

        assertEquals(1, count("app_log"));
    }

    private int count(String table) {
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return rows == null ? 0 : rows;
    }

    private String onlyBookId(String table) {
        return jdbc.queryForObject("SELECT book_id FROM " + table, String.class);
    }
}
