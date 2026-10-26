package org.example.fqxs.service;

import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.util.DateUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 审查记录页的数据源（只读）。
 *
 * <p>三块内容对应一条书从"抓正文"到"出结论"的三个阶段：</p>
 * <ol>
 *   <li><b>正在 AI 审查</b>：{@link ReviewActivity} 的内存快照——库里没有"在飞"状态；</li>
 *   <li><b>正常拉取章节</b>：{@code novel_chapter} 按书聚合（V14 起官方前 10 章落库）；</li>
 *   <li><b>判定结果</b>：{@code novel_ai_verdict_log} 的 append-only 流水。</li>
 * </ol>
 *
 * <p>为什么用 JdbcTemplate 而不是仓储：这是纯报表查询，需要 GROUP BY + 聚合 + LIMIT，
 * 用 JPQL 投影需要新建多个接口；读侧不参与任何事务与实体生命周期，
 * 与 {@code AiFaultGuard} / {@code AiUsageRecorder} 的取法一致。</p>
 *
 * <p>列一律按<b>下标</b>取：H2 会把未加引号的别名存成大写，按名字取会遇到大小写不一致。</p>
 */
@Slf4j
@Service
public class RecordQueryService {

    /** 单次返回条数上限：章节聚合与流水各算一次，避免一次刷新拉出几千行。 */
    private static final int MAX_LIMIT = 500;

    /** 书名回填的 IN 上限：两段结果最多各 MAX_LIMIT 条，去重后不会超过这个数。 */
    private static final int MAX_TITLE_IDS = 1000;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    private final ReviewActivity reviewActivity;

    public RecordQueryService(JdbcTemplate jdbcTemplate, ReviewActivity reviewActivity) {
        this.jdbcTemplate = jdbcTemplate;
        this.reviewActivity = reviewActivity;
    }

    /**
     * 正在审查的一条（内存态）。
     *
     * @param elapsedSeconds 已用时，由开始时刻现算
     */
    public record RunningRow(String bookId, String title, String kind, String kindLabel, String engine,
                             String detail, long elapsedSeconds) {
    }

    /**
     * 一本书的章节拉取情况。
     *
     * @param chapters      已缓存章节数
     * @param chars         正文字数合计
     * @param maxIndex      最大章号（0-based，页面显示时 +1）
     * @param lastFetched   最近一次落库时刻
     * @param puaUnresolved 解码后仍残留的私用区字符数（正常为 0）
     */
    public record ChapterFetch(String bookId, String title, long chapters, long chars, int maxIndex,
                               String lastFetched, long puaUnresolved) {
    }

    /**
     * 一条判定流水。
     *
     * @param aiFlag 1=非 AI 2=AI 3=疑似
     * @param kind   sweep / verify / progress / rerun
     * @param chaptersChecked 逐章校验的进度计数（该型号已完成几章）
     * @param chapterNo       本次实际校验的章号（1-based），非逐章动作为 0
     * @param sampleSpan      取样跨度（如 {@code 1-4,7,12}），非初筛动作为 null
     */
    public record VerdictRow(String bookId, String title, String engine, String model, int aiFlag,
                             Double aiScore, String reason, String kind, int chaptersChecked,
                             int chapterNo, String sampleSpan, String checkedTime) {
    }

    /**
     * 页面一次要的全部数据。
     *
     * @param tally 窗口内流水按 aiFlag 计数（"1"/"2"/"3"/"0" → 条数）
     */
    public record Overview(int hours, String from, List<RunningRow> running, List<ChapterFetch> chapters,
                           List<VerdictRow> results, Map<String, Long> tally) {
    }

    /**
     * 组装审查记录页的数据。
     *
     * @param hours 时间窗口（小时），调用方已钳好范围
     * @param limit 每块最多多少条，调用方已钳好范围
     * @return 三块数据 + 窗口内结论分布
     */
    public Overview overview(int hours, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        LocalDateTime from = DateUtil.now().minusHours(hours);
        // 取一次快照供两处共用：两次取之间若有任务开始/结束，书名与运行中列表会来自不同时刻
        List<ReviewActivity.Running> snapshot = reviewActivity.snapshot();

        List<ChapterFetch> chapters = chapterFetches(from, safeLimit);
        List<VerdictRow> results = verdictLogs(from, safeLimit);

        Set<String> bookIds = new LinkedHashSet<>();
        chapters.forEach(row -> bookIds.add(row.bookId()));
        results.forEach(row -> bookIds.add(row.bookId()));
        snapshot.forEach(row -> {
            if (row.bookId() != null && !row.bookId().isBlank()) {
                bookIds.add(row.bookId());
            }
        });
        Map<String, String> titles = titles(bookIds);

        long now = System.currentTimeMillis();
        List<RunningRow> running = new ArrayList<>();
        for (ReviewActivity.Running item : snapshot) {
            running.add(new RunningRow(item.bookId(), title(titles, item.bookId()), item.kind(),
                    item.kindLabel(), item.engine(), item.detail(),
                    Math.max(0L, (now - item.startedAt()) / 1000)));
        }

        List<ChapterFetch> withTitles = chapters.stream()
                .map(row -> new ChapterFetch(row.bookId(), title(titles, row.bookId()), row.chapters(),
                        row.chars(), row.maxIndex(), row.lastFetched(), row.puaUnresolved()))
                .toList();
        List<VerdictRow> resultsWithTitles = results.stream()
                .map(row -> new VerdictRow(row.bookId(), title(titles, row.bookId()), row.engine(), row.model(),
                        row.aiFlag(), row.aiScore(), row.reason(), row.kind(), row.chaptersChecked(),
                        row.chapterNo(), row.sampleSpan(), row.checkedTime()))
                .toList();

        return new Overview(hours, from.format(TIME), running, withTitles, resultsWithTitles,
                tally(from));
    }

    /** 章节拉取：按书聚合，最近拉过的排前面。 */
    private List<ChapterFetch> chapterFetches(LocalDateTime from, int limit) {
        String sql = """
                SELECT book_id, COUNT(*), COALESCE(SUM(char_count), 0), COALESCE(MAX(chapter_index), 0),
                       MAX(fetched_time), COALESCE(SUM(pua_unresolved), 0)
                FROM novel_chapter
                WHERE fetched_time >= ?
                GROUP BY book_id
                ORDER BY MAX(fetched_time) DESC
                LIMIT ?
                """;
        return jdbcTemplate.query(sql, (rs, index) -> new ChapterFetch(
                rs.getString(1), null, rs.getLong(2), rs.getLong(3), rs.getInt(4),
                format(rs, 5), rs.getLong(6)), Timestamp.valueOf(from), limit);
    }

    /** 判定流水：append-only，新的在前。 */
    private List<VerdictRow> verdictLogs(LocalDateTime from, int limit) {
        // 按列下标取：H2 会把别名统一成大写，按名取换个驱动就 NPE
        String sql = """
                SELECT book_id, engine, model, ai_flag, ai_score, reason, kind, chapters_checked,
                       chapter_no, sample_span, checked_time
                FROM novel_ai_verdict_log
                WHERE checked_time >= ?
                ORDER BY checked_time DESC, id DESC
                LIMIT ?
                """;
        return jdbcTemplate.query(sql, (rs, index) -> new VerdictRow(
                rs.getString(1), null, rs.getString(2), rs.getString(3), rs.getInt(4),
                score(rs, 5), rs.getString(6), rs.getString(7), rs.getInt(8),
                rs.getInt(9), rs.getString(10), format(rs, 11)), Timestamp.valueOf(from), limit);
    }

    /** 窗口内流水按标记计数：页面顶部的 KPI 用它，不受 limit 截断影响。 */
    private Map<String, Long> tally(LocalDateTime from) {
        /* 失败行（kind=failed）的 ai_flag 也是 0，但它不是「判了但没命中」而是「没判成」。
           混进 0 档会让页面 KPI 的「未命中」看起来像已经判过，失败率也就无从体现 */
        String sql = "SELECT ai_flag, COUNT(*) FROM novel_ai_verdict_log "
                + "WHERE checked_time >= ? AND kind <> 'failed' GROUP BY ai_flag";
        Map<String, Long> tally = new LinkedHashMap<>();
        tally.put("0", 0L);
        tally.put("1", 0L);
        tally.put("2", 0L);
        tally.put("3", 0L);
        jdbcTemplate.query(sql, rs -> {
            tally.put(String.valueOf(rs.getInt(1)), rs.getLong(2));
        }, Timestamp.valueOf(from));
        return tally;
    }

    /** 书名回填：同一本书在多榜有多行，取 MIN(title) 即可（同名）。 */
    private Map<String, String> titles(Set<String> bookIds) {
        if (bookIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<String> ids = bookIds.stream().limit(MAX_TITLE_IDS).toList();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        String sql = "SELECT book_id, MIN(title) FROM novel_rank WHERE book_id IN (" + placeholders
                + ") GROUP BY book_id";
        Map<String, String> titles = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            titles.put(rs.getString(1), rs.getString(2));
        }, ids.toArray());
        return titles;
    }

    private static String title(Map<String, String> titles, String bookId) {
        if (bookId == null) {
            return "";
        }
        String title = titles.get(bookId);
        // 书可能已被清空分类删掉，书名回退成 ID，页面上仍能定位是哪一本
        return title == null || title.isBlank() ? bookId : title;
    }

    private static String format(ResultSet rs, int column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? "" : value.toLocalDateTime().format(TIME);
    }

    private static Double score(ResultSet rs, int column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }
}
