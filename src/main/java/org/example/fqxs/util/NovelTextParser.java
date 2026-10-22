package org.example.fqxs.util;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 排行榜 JSON 的字段取值与文案归一化。
 * 番茄接口同一字段存在 camel / snake 两种写法，这里统一按候选名顺序回退取值。
 */
public final class NovelTextParser {

    private NovelTextParser() {
    }

    public static String firstText(JsonNode node, String... fieldNames) {
        if (node == null) {
            return "";
        }
        for (String name : fieldNames) {
            JsonNode child = node.path(name);
            if (!child.isMissingNode() && !child.isNull()) {
                String value = child.asText();
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return "";
    }

    public static Long firstLong(JsonNode node, String... fieldNames) {
        String value = firstText(node, fieldNames);
        if (value.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 列出节点上实际存在的字段名，用于接口字段变更时快速定位。 */
    public static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        if (node != null) {
            node.fieldNames().forEachRemaining(names::add);
        }
        return names;
    }

    public static String parseStatus(JsonNode book) {
        String status = firstText(book, "creationStatus", "creation_status", "status");
        if (status.isEmpty()) {
            return "未知";
        }
        if (status.matches("\\d+")) {
            return Integer.parseInt(status) == 0 ? "已完结" : "连载中";
        }
        if (status.contains("连载")) {
            return "连载中";
        }
        if (status.contains("完结")) {
            return "已完结";
        }
        return status;
    }

    /** 大数折成 “x.x万”，与站外展示保持一致。 */
    public static String formatCount(long num) {
        return num >= 10000 ? String.format("%.1f万", num / 10000.0) : String.valueOf(num);
    }

    public static String formatCount(JsonNode book, String... fieldNames) {
        String raw = firstText(book, fieldNames);
        if (raw.isEmpty()) {
            return "";
        }
        try {
            return formatCount(Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    /**
     * “12.3万” / “123000” 统一折成“万”为单位的数值，供字数筛选使用。
     * 无法解析时返回 0，调用方按“不满足任何字数区间”处理。
     */
    public static double parseWordCountToWan(String wordCount) {
        if (wordCount == null) {
            return 0;
        }
        String value = wordCount.trim();
        if (value.isEmpty()) {
            return 0;
        }
        try {
            return value.endsWith("万")
                    ? Double.parseDouble(value.substring(0, value.length() - 1))
                    : Double.parseDouble(value) / 10000.0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
