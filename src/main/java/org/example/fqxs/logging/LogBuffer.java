package org.example.fqxs.logging;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 日志落库的入口缓冲：三类日志各一条有界队列，生产者（logback appender / 拦截器 / 客户端）
 * 只做 {@code offer}，消费者 {@link LogDbWriter} 每 250ms 批量取走写库。
 *
 * <p><b>为什么是静态的</b>：{@link DbLogAppender} 由 logback 在 Spring 容器之前实例化，
 * 拿不到任何 Bean。静态队列是这两套生命周期之间唯一干净的接口。</p>
 *
 * <p><b>为什么必须有界</b>：数据库写不动（锁表、磁盘满、迁移未完成）时，无界队列会一路吃内存
 * 直到 OOM——日志系统把业务拖死是最不能接受的失败模式。满了两千条就丢，丢多少记在
 * {@link #droppedApp()} 等计数里，由 {@link LogDbWriter} 定期用 stderr 报出来。
 * 丢弃计数不写日志：日志失败再写日志会自激。</p>
 */
public final class LogBuffer {

    /** 每条队列的容量：按「250ms 落一次、一次 500 条」估算，够扛住约 40 秒的写入停顿。 */
    public static final int CAPACITY = 20_000;

    private static final BlockingQueue<AppLogEntry> APP = new ArrayBlockingQueue<>(CAPACITY);
    private static final BlockingQueue<ApiLogEntry> API = new ArrayBlockingQueue<>(CAPACITY);
    private static final BlockingQueue<ThirdPartyLogEntry> THIRD_PARTY = new ArrayBlockingQueue<>(CAPACITY);

    private static final AtomicLong APP_DROPPED = new AtomicLong();
    private static final AtomicLong API_DROPPED = new AtomicLong();
    private static final AtomicLong THIRD_PARTY_DROPPED = new AtomicLong();

    private LogBuffer() {
    }

    public static void offerApp(AppLogEntry entry) {
        if (entry != null && !APP.offer(entry)) {
            APP_DROPPED.incrementAndGet();
        }
    }

    public static void offerApi(ApiLogEntry entry) {
        if (entry != null && !API.offer(entry)) {
            API_DROPPED.incrementAndGet();
        }
    }

    public static void offerThirdParty(ThirdPartyLogEntry entry) {
        if (entry != null && !THIRD_PARTY.offer(entry)) {
            THIRD_PARTY_DROPPED.incrementAndGet();
        }
    }

    /** 取走最多 {@code max} 条追加到 {@code sink}，返回实际条数。 */
    public static int drainApp(List<AppLogEntry> sink, int max) {
        return APP.drainTo(sink, max);
    }

    public static int drainApi(List<ApiLogEntry> sink, int max) {
        return API.drainTo(sink, max);
    }

    public static int drainThirdParty(List<ThirdPartyLogEntry> sink, int max) {
        return THIRD_PARTY.drainTo(sink, max);
    }

    /** 当前积压条数，供查询接口与排查用。 */
    public static int pendingApp() {
        return APP.size();
    }

    public static int pendingApi() {
        return API.size();
    }

    public static int pendingThirdParty() {
        return THIRD_PARTY.size();
    }

    public static long droppedApp() {
        return APP_DROPPED.get();
    }

    public static long droppedApi() {
        return API_DROPPED.get();
    }

    public static long droppedThirdParty() {
        return THIRD_PARTY_DROPPED.get();
    }

    /** 单测清场用：丢弃计数清零（队列本身由各用例自行 drain）。 */
    static void resetDroppedCounters() {
        APP_DROPPED.set(0);
        API_DROPPED.set(0);
        THIRD_PARTY_DROPPED.set(0);
    }
}
