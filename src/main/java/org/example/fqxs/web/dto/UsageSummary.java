package org.example.fqxs.web.dto;

import java.util.List;

/** 近 N 天调用用量：按日、按引擎与合计，字段名与密钥面板一致。 */
public record UsageSummary(List<Day> days, List<Engine> byEngine, Totals totals) {

    public record Day(String date, long calls, long promptTokens, long completionTokens) {
    }

    public record Engine(String engine, long calls, long promptTokens, long completionTokens) {
    }

    public record Totals(long calls, long promptTokens, long completionTokens) {
    }
}
