package org.example.fqxs.exception;

/**
 * AI 日调用配额用尽（安全测试报告 M1）。
 *
 * <p>单独一个类型是为了让全局异常处理器回 429 而不是 500：请求本身没写错，
 * 是今天的额度到顶了，前端要能区分「再等等」和「我哪里填错了」。</p>
 */
public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException(String message) {
        super(message);
    }
}
