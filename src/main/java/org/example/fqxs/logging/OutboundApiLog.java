package org.example.fqxs.logging;

import okhttp3.HttpUrl;
import org.example.fqxs.util.DateUtil;

/**
 * 出站官方 API 的日志埋点（{@code api_log.direction=OUT}）。
 *
 * <p>只有两个调用点：{@code FanqieApiClient}（榜单）与 {@code ChapterClient}（目录 / 正文）。
 * 第三方线路不走这里——它在 {@code ThirdPartySources} 里记 {@code third_party_log}，
 * 因为第三方要按线路看健康度，官方接口没有「换线」这回事。</p>
 *
 * <p>target 只留 path、query 单独一列：榜单 URL 带七八个参数，
 * 塞进一列会让「按接口聚合」变成字符串拼接。</p>
 */
public final class OutboundApiLog {

    private OutboundApiLog() {
    }

    /**
     * 记一次出站调用。
     *
     * @param url        完整请求地址（用来解析 path / query / book_id）
     * @param status     HTTP 状态码，没拿到响应为 null
     * @param bytes      响应体字符数，未知为 null
     * @param durationMs 耗时
     * @param outcome    {@link ApiLogEntry#OUTCOME_OK} / {@link ApiLogEntry#OUTCOME_ERROR}
     * @param error      失败原因，成功为 null
     */
    public static void record(String url, Integer status, Integer bytes, long durationMs,
                              String outcome, String error) {
        try {
            HttpUrl parsed = url == null ? null : HttpUrl.parse(url);
            String target = parsed == null ? url : parsed.encodedPath();
            String query = parsed == null ? null : parsed.query();
            String bookId = parsed == null ? null : parsed.queryParameter("book_id");
            LogBuffer.offerApi(new ApiLogEntry(
                    DateUtil.now(),
                    ApiLogEntry.DIRECTION_OUT,
                    "GET",
                    AppLogEntry.truncate(target, ApiLogEntry.TARGET_MAX),
                    AppLogEntry.truncate(query, ApiLogEntry.QUERY_MAX),
                    AppLogEntry.truncate(bookId, ApiLogEntry.BOOK_ID_MAX),
                    status,
                    (int) Math.min(Integer.MAX_VALUE, Math.max(0, durationMs)),
                    bytes,
                    null,
                    null,
                    null,
                    outcome,
                    AppLogEntry.truncate(error, ApiLogEntry.ERROR_MAX)));
        } catch (RuntimeException ignored) {
            // 埋点失败不能影响抓取
        }
    }
}
