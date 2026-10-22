package org.example.fqxs.web;

import java.util.List;

/**
 * 强类型响应包装（ApiResult 的 record 版）：JSON 形状与原 Map 版完全一致，
 * code=0 成功；data/total 为空时由 Jackson 的 non_null 策略省略。
 * 控制器返回它，springdoc 才能把 data 的字段展开进 OpenAPI。
 */
public record ApiResponse<T>(int code, String message, T data, Integer total) {

    public static <T> ApiResponse<T> data(String message, T data) {
        return new ApiResponse<>(0, message, data, null);
    }

    public static <T> ApiResponse<List<T>> list(List<T> data) {
        List<T> safe = data == null ? List.of() : data;
        return new ApiResponse<>(0, "success", safe, safe.size());
    }

    public static ApiResponse<Void> ok(String message) {
        return new ApiResponse<>(0, message, null, null);
    }

    public static ApiResponse<Void> fail(int code, String message) {
        return new ApiResponse<>(code, message, null, null);
    }
}
