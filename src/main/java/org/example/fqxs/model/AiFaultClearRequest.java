package org.example.fqxs.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 解除某引擎自动停用的请求。
 *
 * @param engine 引擎标识
 */
public record AiFaultClearRequest(@NotBlank String engine) {
}
