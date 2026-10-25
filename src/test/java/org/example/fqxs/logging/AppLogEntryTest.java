package org.example.fqxs.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 程序日志落库行的转换契约：截断、bookId 取自 MDC、异常栈可读。 */
class AppLogEntryTest {

    /**
     * 必须用全局 LoggerContext：MDC 写入的是 SLF4J 绑定的那个 adapter，
     * 自建 {@code new LoggerContext()} 会带自己的 adapter，读不到测试里 put 的值。
     */
    private final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

    @Test
    void bookIdComesFromMdcAndIsRestoredAfterClose() {
        try (BookLogContext ignored = BookLogContext.open("7123456789")) {
            assertEquals("7123456789", AppLogEntry.of(event("hello")).bookId());
        }
        assertNull(AppLogEntry.of(event("hello")).bookId());
    }

    @Test
    void nestedContextRestoresOuterBookId() {
        try (BookLogContext outer = BookLogContext.open("outer")) {
            try (BookLogContext inner = BookLogContext.open("inner")) {
                assertEquals("inner", AppLogEntry.of(event("x")).bookId());
            }
            assertEquals("outer", AppLogEntry.of(event("x")).bookId());
        }
        assertNull(AppLogEntry.of(event("x")).bookId());
    }

    @Test
    void blankBookIdIsTreatedAsNoContext() {
        try (BookLogContext ignored = BookLogContext.open("   ")) {
            assertNull(AppLogEntry.of(event("x")).bookId());
        }
    }

    @Test
    void longMessageIsTruncatedToColumnWidth() {
        String longMessage = "长".repeat(AppLogEntry.MESSAGE_MAX + 100);

        AppLogEntry entry = AppLogEntry.of(event(longMessage));

        assertEquals(AppLogEntry.MESSAGE_MAX, entry.message().length());
        assertTrue(entry.message().endsWith("…"));
    }

    @Test
    void exceptionCarriesClassNameAndFrames() {
        RuntimeException failure = new IllegalStateException("线路挂了");

        AppLogEntry entry = AppLogEntry.of(eventWithThrowable(failure));

        assertTrue(entry.exception().startsWith("java.lang.IllegalStateException: 线路挂了"));
        assertTrue(entry.exception().contains("at "));
    }

    @Test
    void levelAndThreadAreKept() {
        AppLogEntry entry = AppLogEntry.of(event("hi"));

        assertEquals("INFO", entry.level());
        assertEquals("book-meta-backfill", entry.thread());
        assertEquals("org.example.fqxs.Test", entry.logger());
    }

    private ILoggingEvent event(String message) {
        return eventWithThrowable(null, message);
    }

    private ILoggingEvent eventWithThrowable(Throwable failure) {
        return eventWithThrowable(failure, "boom");
    }

    private ILoggingEvent eventWithThrowable(Throwable failure, String message) {
        Logger logger = context.getLogger("org.example.fqxs.Test");
        LoggingEvent event = new LoggingEvent("fqcn", logger, Level.INFO, message, failure, null);
        event.setThreadName("book-meta-backfill");
        return event;
    }
}
