package org.example.fqxs.service;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 正在进行的 AI 审查（内存态，单进程）。
 *
 * <p>为什么需要它：判定是同步请求，库里只有「已经落下的结论」，没有任何地方记着
 * 「这一本正在判」。审查记录页要显示"正在 AI 审查"，就得有人记着在飞的动作。</p>
 *
 * <p>为什么放内存而不是建表：这是瞬时状态，进程重启后"正在审查"本来就是空——
 * 落库反而要写「进程崩了留下的僵尸行」的清理逻辑。条目只由
 * {@code AiDetectController} 在调服务前后 begin/end，异常路径靠 finally 收尾。</p>
 *
 * <p>容量兜底：同时最多跟踪 {@link #MAX_RUNNING} 条，超了丢最早的。
 * 正常并发是 1（爬取锁 + 单批上限），这个上限只是防止某条 end 漏掉后无限堆积。</p>
 */
@Component
public class ReviewActivity {

    /** 同时跟踪的上限：正常远达不到，纯粹是漏掉 end 时的兜底。 */
    static final int MAX_RUNNING = 64;

    /** 动作类型：与页面上「正在做什么」的三档一一对应。 */
    public enum Kind {
        /** 单本整本判定。 */
        DETECT("单本判定"),
        /** 批量初筛（一批 N 本）。 */
        SWEEP("批量初筛"),
        /** 逐章校验推进一步。 */
        VERIFY("逐章校验");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 一条在飞记录。
     *
     * @param id       结束时的凭据
     * @param kind     动作类型
     * @param bookId   书籍 ID；批量初筛没有单本书，为 null
     * @param engine   引擎标识
     * @param detail   页面上的补充说明（例如「一批 5 本」）
     * @param startedAt 开始时刻（epoch 毫秒），页面据此算已用时
     */
    public record Running(String id, String kind, String kindLabel, String bookId, String engine,
                          String detail, long startedAt) {
    }

    private final Map<String, Running> running = new ConcurrentHashMap<>();

    /**
     * 登记一条在飞记录。
     *
     * @return 结束时要传回的 id
     */
    public String begin(Kind kind, String bookId, String engine, String detail) {
        if (running.size() >= MAX_RUNNING) {
            running.values().stream()
                    .min(Comparator.comparingLong(Running::startedAt))
                    .ifPresent(oldest -> running.remove(oldest.id()));
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        running.put(id, new Running(id, kind.name(), kind.label(), bookId, engine, detail,
                System.currentTimeMillis()));
        return id;
    }

    /** 结束一条记录；id 为空或已被兜底清掉时静默返回。 */
    public void end(String id) {
        if (id != null) {
            running.remove(id);
        }
    }

    /** 当前在飞清单，按开始时间正序（先开始的排前面）。 */
    public List<Running> snapshot() {
        return running.values().stream()
                .sorted(Comparator.comparingLong(Running::startedAt))
                .toList();
    }

    /** 在飞条数：审查记录页据此决定要不要继续轮询。 */
    public int size() {
        return running.size();
    }
}
