package org.example.fqxs.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 只切换一个引擎的启用状态。
 *
 * <p>用于「填了 Key 但先不用」：不携带 baseUrl / model，停用不必回填端点与型号。
 * 允许所有引擎都停用，此时判定接口按「没有可用引擎」报错。</p>
 *
 * @param id      引擎标识
 * @param enabled true 启用、false 停用
 */
public record AiEngineEnabledRequest(@NotBlank String id, @NotNull Boolean enabled) {
}
