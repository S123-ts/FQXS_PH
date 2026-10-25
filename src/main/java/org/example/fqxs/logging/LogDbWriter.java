package org.example.fqxs.logging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.util.DateUtil;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 日志落库的消费者：把 {@link LogBuffer} 里三类日志每 250ms 批量写进三张表。
 *
 * <p><b>为什么用 ApplicationRunner 起线程</b>：Flyway 迁移在 DataSource 就绪时执行，
 * 而 {@code @PostConstruct} 的组件可能先于迁移初始化——那时表还不存在，第一批写入必然失败。
 * 等所有 Bean 就绪后再起线程，第一次 flush 就已经能写。</p>
 *
 * <p><b>写入失败绝不外抛、绝不 log</b>：这里一 log 就会再次进入 {@link DbLogAppender}，
 * 队列又被塞满，形成自激。失败统一走 {@code System.err} 且限频 30 秒一次，
 * 同时累加计数供排查。</p>
 *
 * <p><b>关闭时做最后一次 flush</b>：{@link DisposableBean#destroy()} 里 drain 一轮，
 * 尽量不丢掉「应用正在关闭」这条日志——排查意外退出时它往往是唯一的线索。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(200) // 晚于其它启动任务：先让建表/回填跑完，日志写入不参与启动顺序
public class LogDbWriter implements ApplicationRunner, DisposableBean {

    /** 单批最多写多少条：太大则单事务过长，太小则 CPU 全耗在往返上。 */
    static final int BATCH_SIZE = 500;

    /** 落库间隔：250ms 意味着日志从产生到可查最多延迟四分之一秒。 */
    static final long FLUSH_INTERVAL_MILLIS = 250;

    /** 失败上报的限频窗口，避免数据库长时间不可用时刷屏。 */
    private static final long FAILURE_REPORT_INTERVAL_MILLIS = 30_000L;

    private static final String INSERT_APP = """
            INSERT INTO app_log (log_time, level, thread, logger, book_id, message, exception, created_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_API = """
            INSERT INTO api_log (direction, api_time, method, target, query, book_id, status, duration_ms,
                                 bytes, client_ip, username, user_agent, outcome, error, created_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_THIRD_PARTY = """
            INSERT INTO third_party_log (request_time, line, path, scene, param_name, param_value,
                                         book_id, status, bytes, duration_ms, outcome, error, created_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final LogDbConfig config;

    private volatile boolean running;
    private Thread worker;

    @Override
    public void run(ApplicationArguments args) {
        // 总开关关掉时仍然起线程：appender 还挂着，不排空的话两万条会白占内存。
        // 排空即丢弃，写库条件在每个 flushXxx 里统一判。
        running = true;
        worker = new Thread(this::loop, "log-db-writer");
        worker.setDaemon(true);
        worker.start();
        if (!config.isEnabled()) {
            log.info("日志落库已关闭（app.log-db.enabled=false），队列只排空不写库，日志只留控制台与文件");
            return;
        }
        log.info("日志落库线程已开启: 程序日志 {} / API {} / 第三方 {}，批量 {} 条、{} ms 一次",
                config.isAppEnabled() ? "开" : "关",
                config.isApiEnabled() ? "开" : "关",
                config.isThirdPartyEnabled() ? "开" : "关",
                BATCH_SIZE, FLUSH_INTERVAL_MILLIS);
    }

    @Override
    public void destroy() {
        running = false;
        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
        }
        // 关闭前把队列里剩下的写掉：这一批往往包含「应用正在关闭」与关闭原因
        try {
            flushAll();
        } catch (RuntimeException ignored) {
            // 关闭路径上不能再抛
        }
    }

    private void loop() {
        long lastFailureReport = 0L;
        long lastDropReport = System.currentTimeMillis();
        while (running) {
            try {
                int written;
                do {
                    written = flushAll();
                } while (written >= BATCH_SIZE && running);
            } catch (RuntimeException failure) {
                long now = System.currentTimeMillis();
                if (now - lastFailureReport >= FAILURE_REPORT_INTERVAL_MILLIS) {
                    lastFailureReport = now;
                    reportFailure(failure);
                }
            }
            long now = System.currentTimeMillis();
            if (now - lastDropReport >= FAILURE_REPORT_INTERVAL_MILLIS) {
                lastDropReport = now;
                reportDropped();
            }
            sleepQuietly();
        }
    }

    /**
     * 取一批写一批，返回本轮实际写入（或被丢弃）的条数。
     *
     * <p>某类日志关掉时仍然 drain：队列必须保持流动，否则满了两万条之后
     * 「丢弃计数」会一直涨，看起来像写入出了问题。</p>
     */
    int flushAll() {
        int total = 0;
        total += flushApp();
        total += flushApi();
        total += flushThirdParty();
        return total;
    }

    private int flushApp() {
        List<AppLogEntry> rows = new ArrayList<>(BATCH_SIZE);
        if (LogBuffer.drainApp(rows, BATCH_SIZE) == 0) {
            return 0;
        }
        if (!config.isEnabled() || !config.isAppEnabled()) {
            return rows.size();
        }
        LocalDateTime createdAt = DateUtil.now();
        jdbcTemplate.batchUpdate(INSERT_APP, rows, rows.size(), (statement, row) -> {
            statement.setObject(1, row.logTime());
            statement.setString(2, row.level());
            statement.setString(3, row.thread());
            statement.setString(4, row.logger());
            statement.setString(5, row.bookId());
            statement.setString(6, row.message());
            statement.setString(7, row.exception());
            statement.setObject(8, createdAt);
        });
        return rows.size();
    }

    private int flushApi() {
        List<ApiLogEntry> rows = new ArrayList<>(BATCH_SIZE);
        if (LogBuffer.drainApi(rows, BATCH_SIZE) == 0) {
            return 0;
        }
        if (!config.isEnabled() || !config.isApiEnabled()) {
            return rows.size();
        }
        LocalDateTime createdAt = DateUtil.now();
        jdbcTemplate.batchUpdate(INSERT_API, rows, rows.size(), (statement, row) -> {
            statement.setString(1, row.direction());
            statement.setObject(2, row.apiTime());
            statement.setString(3, row.method());
            statement.setString(4, row.target());
            statement.setString(5, row.query());
            statement.setString(6, row.bookId());
            statement.setObject(7, row.status());
            statement.setInt(8, row.durationMs());
            statement.setObject(9, row.bytes());
            statement.setString(10, row.clientIp());
            statement.setString(11, row.username());
            statement.setString(12, row.userAgent());
            statement.setString(13, row.outcome());
            statement.setString(14, row.error());
            statement.setObject(15, createdAt);
        });
        return rows.size();
    }

    private int flushThirdParty() {
        List<ThirdPartyLogEntry> rows = new ArrayList<>(BATCH_SIZE);
        if (LogBuffer.drainThirdParty(rows, BATCH_SIZE) == 0) {
            return 0;
        }
        if (!config.isEnabled() || !config.isThirdPartyEnabled()) {
            return rows.size();
        }
        LocalDateTime createdAt = DateUtil.now();
        jdbcTemplate.batchUpdate(INSERT_THIRD_PARTY, rows, rows.size(), (statement, row) -> {
            statement.setObject(1, row.requestTime());
            statement.setString(2, row.line());
            statement.setString(3, row.path());
            statement.setString(4, row.scene());
            statement.setString(5, row.paramName());
            statement.setString(6, row.paramValue());
            statement.setString(7, row.bookId());
            statement.setObject(8, row.status());
            statement.setObject(9, row.bytes());
            statement.setInt(10, row.durationMs());
            statement.setString(11, row.outcome());
            statement.setString(12, row.error());
            statement.setObject(13, createdAt);
        });
        return rows.size();
    }

    private void reportFailure(RuntimeException failure) {
        // 用 stderr 而不是 log：日志写入失败时再 log 会重新入队，形成自激
        System.err.println("[log-db] 日志写入失败，本批已丢弃: " + failure.getClass().getSimpleName()
                + ": " + failure.getMessage());
    }

    private void reportDropped() {
        long app = LogBuffer.droppedApp();
        long api = LogBuffer.droppedApi();
        long third = LogBuffer.droppedThirdParty();
        if (app + api + third == 0) {
            return;
        }
        System.err.println("[log-db] 队列已满丢弃（累计）: app=" + app + " api=" + api + " third=" + third
                + "，当前积压 app=" + LogBuffer.pendingApp()
                + " api=" + LogBuffer.pendingApi()
                + " third=" + LogBuffer.pendingThirdParty());
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(FLUSH_INTERVAL_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
