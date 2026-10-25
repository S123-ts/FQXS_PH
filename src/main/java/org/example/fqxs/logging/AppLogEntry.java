package org.example.fqxs.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * 一条程序日志的落库形态（{@code app_log} 的一行）。
 *
 * <p>由 {@link DbLogAppender} 从 logback 事件转出来，转换时刻就把长度截断——日志表是热表，
 * 一条 30KB 的异常栈会把批量写入拖成几十毫秒；超长部分在文件日志里仍完整可查。</p>
 *
 * <p>{@code bookId} 来自 MDC：在「处理某一本书」的代码段用 {@link BookLogContext} 圈定，
 * 期间打的所有日志都会带上这本书的 id。没有圈定的系统级日志（启动、定时任务）为 null，
 * 它们在清理时按天数处理，不跟随书。</p>
 *
 * @param logTime   事件时间（北京时间，与库里其它时刻口径一致）
 * @param level     INFO / WARN / ERROR / DEBUG / TRACE
 * @param thread    线程名，批量任务排查时用来区分 crawl- / book-meta-backfill 等
 * @param logger    logger 名（截断到 {@value #LOGGER_MAX}）
 * @param bookId    所属书 id，系统级日志为 null
 * @param message   格式化后的消息（截断到 {@value #MESSAGE_MAX}）
 * @param exception 异常类名 + 消息 + 栈（截断到 {@value #EXCEPTION_MAX}），无异常为 null
 */
public record AppLogEntry(LocalDateTime logTime, String level, String thread, String logger,
                          String bookId, String message, String exception) {

    /** 与 {@code app_log.message VARCHAR(2000)} 对齐。 */
    public static final int MESSAGE_MAX = 2000;

    /** 与 {@code app_log.exception VARCHAR(4000)} 对齐。 */
    public static final int EXCEPTION_MAX = 4000;

    /** 与 {@code app_log.logger VARCHAR(160)} 对齐。 */
    public static final int LOGGER_MAX = 160;

    /** 与 {@code app_log.thread VARCHAR(80)} 对齐。 */
    public static final int THREAD_MAX = 80;

    /** 与 {@code app_log.book_id VARCHAR(50)} 对齐。 */
    public static final int BOOK_ID_MAX = 50;

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    /** 把 logback 事件转成落库行；本方法必须无异常、无副作用，它在每条日志的同步路径上。 */
    public static AppLogEntry of(ILoggingEvent event) {
        if (event == null) {
            return null;
        }
        return new AppLogEntry(
                LocalDateTime.ofInstant(Instant.ofEpochMilli(event.getTimeStamp()), BEIJING),
                event.getLevel() == null ? "INFO" : event.getLevel().toString(),
                truncate(event.getThreadName(), THREAD_MAX),
                truncate(event.getLoggerName(), LOGGER_MAX),
                bookIdOf(event.getMDCPropertyMap()),
                truncate(event.getFormattedMessage(), MESSAGE_MAX),
                stackOf(event.getThrowableProxy()));
    }

    /** MDC 里没有书上下文（绝大多数系统级日志）时返回 null，不要写空串——清理 SQL 靠 IS NULL 区分。 */
    private static String bookIdOf(Map<String, String> mdc) {
        if (mdc == null || mdc.isEmpty()) {
            return null;
        }
        String bookId = mdc.get(BookLogContext.MDC_KEY);
        if (bookId == null || bookId.isBlank()) {
            return null;
        }
        return truncate(bookId, BOOK_ID_MAX);
    }

    /** 异常转成「类名: 消息 + 逐帧栈」，逐帧拼而不是 getMessage()——批量任务失败时帧信息才是关键。 */
    private static String stackOf(IThrowableProxy proxy) {
        if (proxy == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        builder.append(proxy.getClassName() == null ? "Throwable" : proxy.getClassName());
        if (proxy.getMessage() != null) {
            builder.append(": ").append(proxy.getMessage());
        }
        StackTraceElementProxy[] frames = proxy.getStackTraceElementProxyArray();
        if (frames != null) {
            for (StackTraceElementProxy frame : frames) {
                if (builder.length() >= EXCEPTION_MAX) {
                    break;
                }
                builder.append("\n\tat ").append(frame.getSTEAsString());
            }
        }
        return truncate(builder.toString(), EXCEPTION_MAX);
    }

    /** 截断到列宽；null 原样返回，超长时留省略号，避免看起来像「消息被截掉了却不知道」。 */
    public static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, Math.max(0, max - 1)) + "…";
    }
}
