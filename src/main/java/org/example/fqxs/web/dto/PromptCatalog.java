package org.example.fqxs.web.dto;

import java.util.List;

/** 提示词变体清单与当前选取策略；只回元数据，不回正文。 */
public record PromptCatalog(String strategy, boolean randomAvailable, List<PromptItem> prompts) {

    public record PromptItem(String id, String name, boolean enabled, boolean poolable,
                             int weight, String origin, boolean builtin, String remark) {
    }
}
