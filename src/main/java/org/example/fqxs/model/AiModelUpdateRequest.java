package org.example.fqxs.model;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 切换免费引擎实际调用模型的请求体。
 *
 * <p>两种形态：{@code models} 非空表示勾选型号池（判定时随机取一个）；
 * 否则用 {@code model} 单型号。引擎标识（通道）不变，朱雀会被服务层拒绝。</p>
 *
 * @param engine 引擎标识：glm-4-flash / qianfan-ernie-speed / qwen2.5-7b / 自定义 id
 * @param model  单型号（兼容旧调用）
 * @param models 型号池（多选）
 */
public record AiModelUpdateRequest(
        @NotBlank(message = "engine 不能为空") String engine,
        String model,
        List<String> models) {
}
