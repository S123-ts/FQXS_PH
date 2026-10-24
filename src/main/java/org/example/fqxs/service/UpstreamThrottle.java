package org.example.fqxs.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局上游节拍器：所有会打到番茄上游的请求（AI 取样、书源目录/正文）共用一条时间轴。
 *
 * <p>为什么必须共用：上游风控看的是「这台机器打过来的请求密度」，不是「哪个功能在打」。
 * AI 判定在批量抓章节时若和阅读端的翻章请求各自限速，两条线叠加仍会触发风控。
 * 模型调用不走这里——它不是上游。</p>
 *
 * <p>间隔由调用方传入：AI 判定用 {@code ai.detect.request-interval-millis}（默认 800），
 * 书源翻章用 {@code fqxs.book-source.request-interval-millis}（默认 300，阅读体验优先）。
 * 槽位是共享的，所以任意两次上游请求之间至少间隔调用方各自的间隔。</p>
 */
@Component
public class UpstreamThrottle {

    private final AtomicLong nextSlotMillis = new AtomicLong();

    /**
     * 占用一个上游请求槽：把下一次请求推后 {@code intervalMillis}，必要时睡眠等待。
     *
     * @param intervalMillis 请求间隔；{@code <=0} 表示不限速
     */
    public void awaitSlot(long intervalMillis) {
        if (intervalMillis <= 0) {
            return;
        }
        long slot = nextSlotMillis.updateAndGet(prev ->
                Math.max(prev, System.currentTimeMillis()) + intervalMillis);
        long waitMillis = slot - System.currentTimeMillis();
        if (waitMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(waitMillis);
        } catch (InterruptedException interruptedException) {
            // 中断只说明调用方要收工了，保留中断标记交给上层决定
            Thread.currentThread().interrupt();
        }
    }
}
