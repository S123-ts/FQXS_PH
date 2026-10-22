package org.example.fqxs.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 测试一个已保存引擎的连通性。
 *
 * @param id 引擎标识
 */
public record AiEngineTestRequest(@NotBlank String id) {
}
