package org.example.fqxs.exception;

/**
 * 所有第三方线路都在冷却中（或通道已关闭），本次请求没有发出。
 *
 * <p>调用方据此区分「线路故障」与「线路正在休息」：后台补齐应当等到冷却结束再试，
 * 而不是把它记成一次失败——冷却期的连续快速失败会把补齐全线程拖进更长的停顿。</p>
 */
public class LineCoolingException extends CrawlException {

    public LineCoolingException(String message) {
        super(message);
    }
}
