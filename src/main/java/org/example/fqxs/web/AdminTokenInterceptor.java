package org.example.fqxs.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * /api/db/** 会不可逆地删数据，且 GET 会整表导出，所以这里的方法一律不豁免：
 * 未配置 app.admin-token 时全部 403；配置后要求请求头 X-Admin-Token 等值匹配。
 * 前端页面不依赖这些接口（只用 /api/rank），排查时需自带令牌。
 */
@Slf4j
@Component
public class AdminTokenInterceptor implements HandlerInterceptor {

    static final String HEADER = "X-Admin-Token";

    private final byte[] expectedToken;
    private final ObjectMapper objectMapper;

    public AdminTokenInterceptor(@Value("${app.admin-token:}") String adminToken, ObjectMapper objectMapper) {
        this.expectedToken = adminToken == null ? new byte[0] : adminToken.trim().getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (expectedToken.length == 0) {
            JsonResponses.deny(response, objectMapper, 403, "数据库维护接口已禁用：请先在配置中设置 app.admin-token");
            log.warn("拒绝 {} {}：未配置 app.admin-token", request.getMethod(), request.getRequestURI());
            return false;
        }
        String provided = request.getHeader(HEADER);
        if (provided == null || !MessageDigest.isEqual(expectedToken, provided.getBytes(StandardCharsets.UTF_8))) {
            JsonResponses.deny(response, objectMapper, 401, "缺少或错误的 " + HEADER + " 请求头");
            log.warn("拒绝 {} {}：管理令牌校验失败", request.getMethod(), request.getRequestURI());
            return false;
        }
        return true;
    }
}
