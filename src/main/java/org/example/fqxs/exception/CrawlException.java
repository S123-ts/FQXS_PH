package org.example.fqxs.exception;

/**
 * 上游抓取失败：区别于“抓取成功但无数据”，让调用方能把错误透传给前端。
 *
 * <p>{@code httpStatus} 与 {@code responseExcerpt} 是给失败留证用的：判定链路要把
 * “不行的原因和上游返回”写进 {@code ai_engine_fault}，只有 message 一句话不够排查
 * （429 与 500 的处置完全不同，上游还常在 body 里写具体原因）。两者都可选，
 * 非 HTTP 类失败（超时、解析不出）保持 0 与 null。</p>
 */
public class CrawlException extends RuntimeException {

    /** 上游 HTTP 状态码；0 表示不是 HTTP 层失败。 */
    private final int httpStatus;

    /** 上游响应体片段（调用方已截断），没有则为 null。 */
    private final String responseExcerpt;

    public CrawlException(String message) {
        this(message, 0, null, null);
    }

    public CrawlException(String message, Throwable cause) {
        this(message, 0, null, cause);
    }

    public CrawlException(String message, int httpStatus, String responseExcerpt) {
        this(message, httpStatus, responseExcerpt, null);
    }

    private CrawlException(String message, int httpStatus, String responseExcerpt, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.responseExcerpt = responseExcerpt;
    }

    /** 上游返回了非 2xx：带状态码与响应片段，供失败留证。 */
    public static CrawlException upstream(String message, int httpStatus, String responseExcerpt) {
        return new CrawlException(message, httpStatus, responseExcerpt);
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String responseExcerpt() {
        return responseExcerpt;
    }
}
