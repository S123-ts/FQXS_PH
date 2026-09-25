package org.example.fqxs.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 统一的 {code,message,data,total} 响应包装，前端按 code!==0 判定失败。 */
public final class ApiResult {

    private ApiResult() {
    }

    public static Map<String, Object> ok() {
        return base(0, "success");
    }

    public static Map<String, Object> ok(String message) {
        return base(0, message);
    }

    public static Map<String, Object> data(String message, Object data) {
        Map<String, Object> result = base(0, message);
        result.put("data", data);
        return result;
    }

    public static Map<String, Object> list(List<?> novels) {
        List<?> safe = novels == null ? List.of() : novels;
        Map<String, Object> result = base(0, "success");
        result.put("data", safe);
        result.put("total", safe.size());
        return result;
    }

    public static Map<String, Object> fail(int code, String message) {
        return base(code, message);
    }

    private static Map<String, Object> base(int code, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("message", message);
        return result;
    }
}
