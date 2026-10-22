package org.example.fqxs.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DateUtilTest {

    @Test
    void formatsSecondsAndMillisToTheSameMinute() {
        String bySeconds = DateUtil.formatEpoch(1_700_000_000L);
        String byMillis = DateUtil.formatEpoch(1_700_000_000_000L);
        assertEquals(bySeconds, byMillis);
        assertTrue(bySeconds.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"), "实际: " + bySeconds);
    }

    /** 榜单时刻固定按北京时间读，部署机时区不该改写已有数据的显示值。 */
    @Test
    void alwaysFormatsInBeijingTimeRegardlessOfHostZone() {
        assertEquals("2023-11-15 06:13", DateUtil.formatEpoch(1_700_000_000L));
    }

    @Test
    void returnsEmptyForMissingTimestamps() {
        assertEquals("", DateUtil.formatEpoch(0L));
        assertEquals("", DateUtil.formatEpoch(-1L));
    }
}
