package org.example.fqxs.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UpstreamThrottleTest {

    /** 不限速时不能白等：书源测试与本地调试都靠这条保持响应。 */
    @Test
    void zeroIntervalReturnsImmediately() {
        UpstreamThrottle throttle = new UpstreamThrottle();
        long start = System.currentTimeMillis();
        throttle.awaitSlot(0);
        throttle.awaitSlot(-1);
        assertTrue(System.currentTimeMillis() - start < 100, "不限速时不应产生等待");
    }

    /** 第二次调用必须等到第一个间隔之后——这是“同一台机器别连着打上游”的唯一保证。 */
    @Test
    void consecutiveCallsAreSpacedByTheInterval() {
        UpstreamThrottle throttle = new UpstreamThrottle();
        long start = System.currentTimeMillis();
        throttle.awaitSlot(150);
        throttle.awaitSlot(150);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 120, "两次请求间隔应接近 150ms，实际 " + elapsed + "ms");
    }
}
