package org.example.fqxs.logging;

import java.time.LocalDateTime;

/**
 * 一条第三方抽取日志（{@code third_party_log} 的一行）。
 *
 * <p>粒度是「一次线路尝试」，不是「一次抽取」：{@code ThirdPartySources.fetch} 换线重试时
 * 每条候选线路各留一行，所以一次 /content 抽取可能产出 1–N 行。
 * 这样「哪条线路今天挂了几次、失败形态是什么」才能直接从表里数出来。</p>
 *
 * @param requestTime 请求时刻（北京时间）
 * @param line        线路 base url（如 {@code https://fq-book.netsite.cc}）
 * @param path        接口路径：{@code /info} / {@code /content} / {@code /catalog}
 * @param scene       场景名（第三方详情 / 第三方正文 / 第三方目录），来自调用方
 * @param paramName   上游参数名（{@code book_id} / {@code item_id}）
 * @param paramValue  参数值
 * @param bookId      关联的书 id；/content 这类只有 item_id 的请求由调用链的 MDC 补
 * @param status      HTTP 状态码；连接失败为 null
 * @param bytes       响应体字节数；未读到响应为 null
 * @param durationMs  本次尝试耗时
 * @param outcome     {@link #OK} 等六种取值之一
 * @param error       失败原因（截断），成功为 null
 */
public record ThirdPartyLogEntry(LocalDateTime requestTime, String line, String path, String scene,
                                 String paramName, String paramValue, String bookId,
                                 Integer status, Integer bytes, int durationMs,
                                 String outcome, String error) {

    /** 解析出可用内容——这是唯一的成功口径（状态位不算，见 ThirdPartySources 的类注释）。 */
    public static final String OK = "OK";

    /** 非 2xx。 */
    public static final String HTTP_ERROR = "HTTP_ERROR";

    /** HTTP 200 但响应体为空：共享线路的典型挂法。 */
    public static final String EMPTY = "EMPTY";

    /** 连不上 / 超时 / 读响应失败。 */
    public static final String IO_ERROR = "IO_ERROR";

    /** 拿到了响应但解析不出来（含「isSuccess:true 却把故障堆栈塞进 data」）。 */
    public static final String PARSE_ERROR = "PARSE_ERROR";

    /** 书已下架 / 正文不存在：线路是好的，这一本书没有数据，不冷却线路。 */
    public static final String ITEM_UNAVAILABLE = "ITEM_UNAVAILABLE";

    public static final int LINE_MAX = 200;
    public static final int PATH_MAX = 40;
    public static final int SCENE_MAX = 40;
    public static final int PARAM_NAME_MAX = 40;
    public static final int PARAM_VALUE_MAX = 80;
    public static final int BOOK_ID_MAX = 50;
    public static final int ERROR_MAX = 1000;
}
