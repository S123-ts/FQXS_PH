package org.example.fqxs.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 保存 / 清除单个引擎密钥的请求体。
 *
 * <p>{@code key} 允许为空：空串表示清除该引擎的密钥（页面上的「清除」按钮）。
 * 接口只接受这三种 engine 值，未知值由服务层拒绝，避免把任意字符串写进配置文件。</p>
 *
 * @param engine 引擎标识：glm-4-flash / qwen2.5-7b / zhuque-text
 * @param key    API Key；空串或 null 表示清除
 */
public record AiKeyUpdateRequest(
        @NotBlank(message = "engine 不能为空") String engine,
        String key) {
}
