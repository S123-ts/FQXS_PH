package org.example.fqxs.logging;

import java.time.LocalDateTime;

/**
 * 一条 API 日志（{@code api_log} 的一行），两个方向共用一张表。
 *
 * <p>{@link #DIRECTION_IN}：别人调本系统，记 method/target/status/耗时/来源 IP 与登录名。
 * 被登录门挡下的请求也会留一行（{@code outcome=DENIED}，status=401），否则「谁在扫接口」看不见。</p>
 *
 * <p>{@link #DIRECTION_OUT}：本系统调番茄官方接口（榜单 / 目录 / 正文），记目标路径、
 * 上游状态码、响应字节数与耗时。第三方线路不走这张表，它在 {@link ThirdPartyLogEntry}。</p>
 *
 * <p>{@code bookId} 尽量填：IN 方向从查询串的 {@code bookId}/{@code book_id} 取，
 * OUT 方向从上游 URL 参数取，取不到就为 null（榜单请求本来就没有单本书的概念）。</p>
 *
 * @param apiTime    发生时刻（北京时间）
 * @param direction  {@link #DIRECTION_IN} / {@link #DIRECTION_OUT}
 * @param method     HTTP 方法
 * @param target     IN 是请求路径（不含 query）；OUT 是上游 URL 的 path
 * @param query      查询串（截断到 {@value #QUERY_MAX}）；OUT 方向可能为 null
 * @param bookId     关联的书 id，取不到为 null
 * @param status     HTTP 状态码；请求没发出去（连接失败）为 null
 * @param durationMs 耗时毫秒
 * @param bytes      响应体字节数；未知为 null
 * @param clientIp   IN 方向的来源 IP，OUT 方向为 null
 * @param username   IN 方向的登录名（未登录为 null），OUT 方向为 null
 * @param userAgent  IN 方向的 UA（截断），OUT 方向为 null
 * @param outcome    OK / ERROR / DENIED
 * @param error      失败原因（截断），成功为 null
 */
public record ApiLogEntry(LocalDateTime apiTime, String direction, String method, String target,
                          String query, String bookId, Integer status, int durationMs, Integer bytes,
                          String clientIp, String username, String userAgent,
                          String outcome, String error) {

    /** 入站：本系统对外提供的接口。 */
    public static final String DIRECTION_IN = "IN";

    /** 出站：打向番茄官方接口的调用。 */
    public static final String DIRECTION_OUT = "OUT";

    /** 正常完成（2xx/3xx）。 */
    public static final String OUTCOME_OK = "OK";

    /** 请求失败（5xx、异常、连接不上）。 */
    public static final String OUTCOME_ERROR = "ERROR";

    /** 被登录门 / 令牌门挡下（401、403）。 */
    public static final String OUTCOME_DENIED = "DENIED";

    public static final int TARGET_MAX = 500;
    public static final int QUERY_MAX = 1000;
    public static final int BOOK_ID_MAX = 50;
    public static final int CLIENT_IP_MAX = 64;
    public static final int USERNAME_MAX = 64;
    public static final int USER_AGENT_MAX = 300;
    public static final int ERROR_MAX = 1000;

    /**
     * 按状态码与异常判定结果：401/403 记 DENIED，其它非 2xx 与异常记 ERROR。
     *
     * <p>分开的理由：DENIED 是「正常的拒绝」，刷屏说明有人在探接口；ERROR 是「服务出问题了」，
     * 两者混成一个值会让告警没法分级。</p>
     */
    public static String outcomeOf(Integer status, Throwable failure) {
        if (failure != null) {
            return OUTCOME_ERROR;
        }
        if (status == null) {
            return OUTCOME_ERROR;
        }
        if (status == 401 || status == 403) {
            return OUTCOME_DENIED;
        }
        return status >= 200 && status < 400 ? OUTCOME_OK : OUTCOME_ERROR;
    }
}
