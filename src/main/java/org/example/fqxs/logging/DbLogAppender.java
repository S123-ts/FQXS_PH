package org.example.fqxs.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

/**
 * logback appender：把每一条日志事件塞进 {@link LogBuffer}，由 {@link LogDbWriter} 异步写库。
 *
 * <p>为什么不用 logback 自带的 DBAppender：它自己管连接池、自己开事务，与 Spring 的
 * Hikari 完全脱节；H2 是单文件库，多一个连接池就多一份锁竞争。这里只做「入队」，
 * 连接与批量写入全部复用应用已有的 JdbcTemplate。</p>
 *
 * <p><b>本类绝不能抛异常、也绝不能在这里打日志</b>：appender 在业务线程的同步路径上，
 * 抛出去会打断业务；在这里 log 会再次触发本 appender，形成自激递归。
 * 因此所有异常一律吞掉，丢弃量由 {@link LogBuffer} 计数、由 writer 用 stderr 报。</p>
 */
public class DbLogAppender extends AppenderBase<ILoggingEvent> {

    @Override
    protected void append(ILoggingEvent event) {
        if (!isStarted() || event == null) {
            return;
        }
        try {
            LogBuffer.offerApp(AppLogEntry.of(event));
        } catch (RuntimeException ignored) {
            // 见类注释：任何异常都必须止步于此
        }
    }
}
