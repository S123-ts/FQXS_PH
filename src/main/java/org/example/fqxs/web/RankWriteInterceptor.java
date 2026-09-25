package org.example.fqxs.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * /api/rank/** 的写操作（抓取入库、拉黑/恢复）改成 POST 后，光靠方法名不足以挡住别的站点
 * 发起跨站请求：这里要求请求带回本会话领取的 X-Rank-Token 自定义头。
 * 跨源脚本不会知道这个随机值，且浏览器要先做预检，因此页面自身能用、外部页面不能用。
 */
@Slf4j
@Component
public class RankWriteInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Rank-Token";

    private static final String SESSION_ATTR = "rankWriteToken";

    private final ObjectMapper objectMapper;

    public RankWriteInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 一个会话只发一次，页面重新加载复用同一枚；会话过期后重新领取。 */
    public static String issueToken(HttpSession session) {
        Object existing = session.getAttribute(SESSION_ATTR);
        if (existing instanceof String token && !token.isEmpty()) {
            return token;
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        session.setAttribute(SESSION_ATTR, token);
        return token;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod())) {
            return true;
        }
        HttpSession session = request.getSession(false);
        String expected = session == null ? null : (String) session.getAttribute(SESSION_ATTR);
        String provided = request.getHeader(HEADER);
        if (expected == null || provided == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            JsonResponses.deny(response, objectMapper, 401,
                    "缺少或错误的 " + HEADER + " 请求头，请先 GET /api/rank/write-token 领取令牌");
            log.warn("拒绝 {} {}：页面写令牌校验失败", request.getMethod(), request.getRequestURI());
            return false;
        }
        return true;
    }
}
