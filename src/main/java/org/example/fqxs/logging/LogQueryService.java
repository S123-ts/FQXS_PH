package org.example.fqxs.logging;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志查询（只读）：给页面与排查脚本用，三条链各一个方法。
 *
 * <p>全部走参数绑定，条件列名在代码里写死——日志表的查询条件来自 URL，
 * 拼接字符串等于把注入面开到最大。</p>
 *
 * <p>返回 {@code Map} 而不是实体：日志表没有业务语义，不需要 JPA 映射，
 * 也用不上乐观锁/级联这些机制；扁平 Map 直接序列化成 JSON 更省事。</p>
 */
@Service
@RequiredArgsConstructor
public class LogQueryService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;
    private final LogDbConfig config;

    /** 程序日志：可按级别与书过滤，新的在前。 */
    public List<Map<String, Object>> recentAppLogs(String level, String bookId, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, log_time, level, thread, logger, book_id, message, exception FROM app_log WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (hasText(level)) {
            sql.append(" AND level = ?");
            args.add(level.trim().toUpperCase());
        }
        if (hasText(bookId)) {
            sql.append(" AND book_id = ?");
            args.add(bookId.trim());
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("time", timeOf(rs, "log_time"));
            row.put("level", rs.getString("level"));
            row.put("thread", rs.getString("thread"));
            row.put("logger", rs.getString("logger"));
            row.put("bookId", rs.getString("book_id"));
            row.put("message", rs.getString("message"));
            row.put("exception", rs.getString("exception"));
            return row;
        }, args.toArray());
    }

    /** API 日志：按方向（IN/OUT）与书过滤。 */
    public List<Map<String, Object>> recentApiLogs(String direction, String bookId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, direction, api_time, method, target, query, book_id, status, duration_ms, bytes,
                       client_ip, username, user_agent, outcome, error
                  FROM api_log WHERE 1=1""");
        List<Object> args = new ArrayList<>();
        if (hasText(direction)) {
            sql.append(" AND direction = ?");
            args.add(direction.trim().toUpperCase());
        }
        if (hasText(bookId)) {
            sql.append(" AND book_id = ?");
            args.add(bookId.trim());
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("time", timeOf(rs, "api_time"));
            row.put("direction", rs.getString("direction"));
            row.put("method", rs.getString("method"));
            row.put("target", rs.getString("target"));
            row.put("query", rs.getString("query"));
            row.put("bookId", rs.getString("book_id"));
            row.put("status", rs.getObject("status"));
            row.put("durationMs", rs.getObject("duration_ms"));
            row.put("bytes", rs.getObject("bytes"));
            row.put("clientIp", rs.getString("client_ip"));
            row.put("username", rs.getString("username"));
            row.put("userAgent", rs.getString("user_agent"));
            row.put("outcome", rs.getString("outcome"));
            row.put("error", rs.getString("error"));
            return row;
        }, args.toArray());
    }

    /** 第三方抽取日志：按线路、书、结果过滤——「这条线路最近挂了没」是最常见的问法。 */
    public List<Map<String, Object>> recentThirdPartyLogs(String line, String bookId, String outcome, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, request_time, line, path, scene, param_name, param_value, book_id,
                       status, bytes, duration_ms, outcome, error
                  FROM third_party_log WHERE 1=1""");
        List<Object> args = new ArrayList<>();
        if (hasText(line)) {
            sql.append(" AND line = ?");
            args.add(line.trim());
        }
        if (hasText(bookId)) {
            sql.append(" AND book_id = ?");
            args.add(bookId.trim());
        }
        if (hasText(outcome)) {
            sql.append(" AND outcome = ?");
            args.add(outcome.trim().toUpperCase());
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("time", timeOf(rs, "request_time"));
            row.put("line", rs.getString("line"));
            row.put("path", rs.getString("path"));
            row.put("scene", rs.getString("scene"));
            row.put("paramName", rs.getString("param_name"));
            row.put("paramValue", rs.getString("param_value"));
            row.put("bookId", rs.getString("book_id"));
            row.put("status", rs.getObject("status"));
            row.put("bytes", rs.getObject("bytes"));
            row.put("durationMs", rs.getObject("duration_ms"));
            row.put("outcome", rs.getString("outcome"));
            row.put("error", rs.getString("error"));
            return row;
        }, args.toArray());
    }

    /** 运行态：开关、队列积压、累计丢弃量。查询本身不碰库，写库卡住时也能用。 */
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", config.isEnabled());
        status.put("appEnabled", config.isAppEnabled());
        status.put("apiEnabled", config.isApiEnabled());
        status.put("thirdPartyEnabled", config.isThirdPartyEnabled());
        status.put("pendingApp", LogBuffer.pendingApp());
        status.put("pendingApi", LogBuffer.pendingApi());
        status.put("pendingThirdParty", LogBuffer.pendingThirdParty());
        status.put("droppedApp", LogBuffer.droppedApp());
        status.put("droppedApi", LogBuffer.droppedApi());
        status.put("droppedThirdParty", LogBuffer.droppedThirdParty());
        status.put("appKeepDays", config.getAppKeepDays());
        status.put("apiKeepDays", config.getApiKeepDays());
        status.put("thirdPartyKeepDays", config.getThirdPartyKeepDays());
        return status;
    }

    /** 单次查询上限，供 controller 钳制入参。 */
    public int maxRows() {
        return Math.max(1, config.getQueryMaxRows());
    }

    private static String timeOf(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime().format(TIME);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
