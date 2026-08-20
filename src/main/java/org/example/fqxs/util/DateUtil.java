// src/main/java/org/example/fqxs/util/DateUtil.java
package org.example.fqxs.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class DateUtil {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static String formatTimestamp(long timestamp) {
        if (timestamp == 0) return "";
        try {
            Instant instant = Instant.ofEpochSecond(timestamp);
            LocalDateTime dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
            return dateTime.format(FORMATTER);
        } catch (Exception e) {
            return String.valueOf(timestamp);
        }
    }
}