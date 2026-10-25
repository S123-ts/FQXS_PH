package org.example.fqxs.logging;

import org.slf4j.MDC;

/**
 * 书级日志上下文：圈定一段代码属于哪本书，期间所有日志（含异常栈）自动带上 bookId。
 *
 * <p>用法是 try-with-resources，退出时恢复上层的值，嵌套调用不会互相踩：</p>
 * <pre>{@code
 * try (BookLogContext ignored = BookLogContext.open(bookId)) {
 *     // 这里打的所有日志都会落进这本书的日志里
 * }
 * }</pre>
 *
 * <p><b>为什么不显式传参</b>：一条判定链路会经过取样器、章节服务、第三方客户端、模型客户端
 * 好几层，逐层加参数会把 bookId 塞进每个方法签名，而它只对日志有意义。MDC 是 SLF4J 给这件事
 * 准备的标准位置，logback 的 {@code %X{bookId}} 与 {@link AppLogEntry} 都读它。</p>
 *
 * <p><b>异步线程不传播</b>：MDC 是 ThreadLocal，提交到线程池的任务拿不到父线程的值。
 * 批量任务（crawl- / book-meta-backfill）必须在任务体内部自己 open 一次，
 * 不能指望提交时带过去。</p>
 */
public final class BookLogContext implements AutoCloseable {

    /** MDC 键名；{@code logback-spring.xml} 的 pattern 与查询接口都按它取。 */
    public static final String MDC_KEY = "bookId";

    /** bookId 为空时的空实现，避免调用方写 if。 */
    private static final BookLogContext NOOP = new BookLogContext(null, false);

    /** 进入前的旧值，关闭时还原。 */
    private final String previous;

    /** 本次是否真的写过 MDC。 */
    private final boolean applied;

    private BookLogContext(String previous, boolean applied) {
        this.previous = previous;
        this.applied = applied;
    }

    /**
     * 圈定一本书。
     *
     * @param bookId 书 id；null / 空白表示不圈定，返回空实现
     */
    public static BookLogContext open(String bookId) {
        if (bookId == null || bookId.isBlank()) {
            return NOOP;
        }
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, bookId);
        return new BookLogContext(previous, true);
    }

    /** 当前线程圈定的书 id，没有则 null。 */
    public static String currentBookId() {
        String bookId = MDC.get(MDC_KEY);
        return bookId == null || bookId.isBlank() ? null : bookId;
    }

    @Override
    public void close() {
        if (!applied) {
            return;
        }
        if (previous == null) {
            MDC.remove(MDC_KEY);
        } else {
            MDC.put(MDC_KEY, previous);
        }
    }
}
