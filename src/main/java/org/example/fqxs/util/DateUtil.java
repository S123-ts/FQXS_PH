package org.example.fqxs.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class DateUtil {

    /** 榜单是中文站的，时刻按北京时间读才对得上；用 systemDefault 会让部署机时区改写历史数据。 */
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** 秒级时间戳在 2286 年前都不会超过该阈值，超过即认为上游给的是毫秒。 */
    private static final long MILLIS_THRESHOLD = 100_000_000_000L;

    private DateUtil() {
    }

    /** 库里所有时刻统一按北京时间落库，避免换台机器就整体偏移。 */
    public static LocalDateTime now() {
        return LocalDateTime.now(BEIJING);
    }

    public static String formatEpoch(long timestamp) {
        if (timestamp <= 0) {
            return "";
        }
        try {
            Instant instant = timestamp >= MILLIS_THRESHOLD
                    ? Instant.ofEpochMilli(timestamp)
                    : Instant.ofEpochSecond(timestamp);
            return LocalDateTime.ofInstant(instant, BEIJING).format(FORMATTER);
        } catch (Exception e) {
            return String.valueOf(timestamp);
        }
    }
}
