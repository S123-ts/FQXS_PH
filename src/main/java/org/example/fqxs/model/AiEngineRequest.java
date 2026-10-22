package org.example.fqxs.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 新增/更新一个 AI 引擎的请求。
 *
 * @param id       引擎标识；新增时留空自动生成，更新时必填
 * @param name     展示名
 * @param baseUrl  OpenAI 兼容端点（裸主机也可，服务端会归一到 /chat/completions）
 * @param model    当前型号
 * @param modelsCsv 可选型号清单，逗号分隔
 * @param enabled  是否启用；null 按启用处理
 * @param priority 熔断顺序，小的先试；null 按 100
 * @param remark   备注
 */
public record AiEngineRequest(String id, String name, @NotBlank String baseUrl, @NotBlank String model,
                              String modelsCsv, Boolean enabled, Integer priority, String remark) {
}
