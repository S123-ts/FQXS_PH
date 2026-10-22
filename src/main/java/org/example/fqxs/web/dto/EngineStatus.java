package org.example.fqxs.web.dto;

import java.util.List;

/**
 * 引擎面板的一行：密钥状态（只含掩码）与可切型号。字段名即前端契约，
 * 改名前先查 static/js/ai_config.js。
 */
public record EngineStatus(
        String name,
        boolean builtin,
        boolean enabled,
        String protocol,
        String baseUrl,
        int priority,
        String remark,
        boolean configured,
        String masked,
        String source,
        String model,
        List<String> modelPool,
        boolean modelSelectable,
        List<String> models,
        List<ModelOption> modelOptions,
        boolean suspended,
        String disableUntil,
        int consecutiveFailures) {

    public record ModelOption(String id, boolean free) {
    }
}
