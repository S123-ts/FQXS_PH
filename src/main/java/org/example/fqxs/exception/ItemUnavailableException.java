package org.example.fqxs.exception;

/**
 * 书级失败：线路本身是好的，但这次请求的那本书不可用（已下架 / 详情或正文不存在）。
 *
 * <p>与 {@link CrawlException} 的唯一区别是冷却语义：多源容灾把普通 {@code CrawlException}
 * 当成「这条线路坏了」并冷却它；一本书的错误不该连累整条线路（共享云手机的容量很小，
 * 一次误冷却就是 5 分钟全体停摆），所以单独成类，由 {@code ThirdPartySources} 直接上抛。</p>
 */
public class ItemUnavailableException extends CrawlException {

    public ItemUnavailableException(String message) {
        super(message);
    }
}
