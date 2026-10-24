package org.example.fqxs.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.config.BookSourceConfig;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 书源可选令牌。
 *
 * <p>{@code /api/bs/**} 在登录门白名单里——Legado 的阅读 App 没法走浏览器登录。
 * 所以它自带的这层令牌是公网暴露时的唯一闸门：{@code fqxs.book-source.token} 留空即不校验（内网），
 * 非空则要求 {@code ?token=} 或 {@code X-Bs-Token} 与配置一致。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookSourceTokenInterceptor implements HandlerInterceptor {

    public static final String TOKEN_HEADER = "X-Bs-Token";

    private final BookSourceConfig bookSourceConfig;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String expected = bookSourceConfig.getToken();
        if (expected == null || expected.isBlank()) {
            return true;
        }
        String supplied = request.getHeader(TOKEN_HEADER);
        if (supplied == null || supplied.isBlank()) {
            supplied = request.getParameter("token");
        }
        if (supplied != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        log.debug("拒绝书源请求 {} {}：令牌无效", request.getMethod(), request.getRequestURI());
        JsonResponses.deny(response, objectMapper, 401, "书源令牌无效");
        return false;
    }
}
