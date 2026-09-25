package org.example.fqxs.exception;

/** 上游抓取失败：区别于“抓取成功但无数据”，让调用方能把错误透传给前端。 */
public class CrawlException extends RuntimeException {

    public CrawlException(String message) {
        super(message);
    }

    public CrawlException(String message, Throwable cause) {
        super(message, cause);
    }
}
