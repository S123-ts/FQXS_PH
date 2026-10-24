package org.example.fqxs.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 全站登录门：除白名单外，所有请求都必须带一个已登录的会话。
 *
 * <p>与另外两个拦截器的分工：</p>
 * <ul>
 *   <li>{@link RankWriteInterceptor}：挡「其他站点冒用浏览器会话」（CSRF，看 {@code X-Rank-Token}）；</li>
 *   <li>{@link AdminTokenInterceptor}：挡「没有管理令牌的删库请求」；</li>
 *   <li>本类：挡「任何知道地址的请求」（未授权访问）。三者叠加，不是替代关系。</li>
 * </ul>
 *
 * <p>响应形态按调用方分流，否则会出现「接口返回 HTML」或「浏览器收到 JSON」：</p>
 * <ul>
 *   <li>接口请求 → 401 + {@code X-Auth-Required: 1}（前端凭这个头跳登录页，不用猜文案）；</li>
 *   <li>页面请求 → 302 到 {@code /login?redirect=原路径}。</li>
 * </ul>
 */
@Slf4j
@Component
public class LoginInterceptor implements HandlerInterceptor {

    /** 前端据此区分「会话过期」与「写令牌失效」——两者都是 401，处理方式完全不同。 */
    public static final String AUTH_REQUIRED_HEADER = "X-Auth-Required";

    static final String LOGIN_PAGE = "/login";

    /**
     * 登录白名单。刻意写死到具体文件而不是 {@code /css/**}、{@code /js/**}：
     * 主界面的样式与脚本本身不含数据，但把它们一并放行会让「未登录也能摸清整个前端的
     * 接口调用面」这件事成立；登录页只用到下面这三个静态文件。
     *
     * <ul>
     *   <li>{@code /error}：异常转发路径，放行否则匿名访问的 404 会变成「跳登录」；</li>
     *   <li>{@code /actuator/health}：反代/探针用，报告已确认它只回 UP/DOWN；</li>
     *   <li>{@code /api/auth/**}：登录、登出、当前身份——不放行连登录都调不到；</li>
     *   <li>{@code /api/bs/**}：Legado 书源端点，阅读 App 没有浏览器会话。它由
     *       {@link BookSourceTokenInterceptor} 按可选令牌单独把关，公网暴露必须配置令牌。</li>
     * </ul>
     */
    public static final String[] WHITELIST = {
            "/login",
            "/login.html",
            "/api/auth/**",
            "/api/bs/**",
            "/css/base.css",
            "/css/login.css",
            "/js/login.js",
            "/favicon.ico",
            "/error",
            "/actuator/health",
            "/actuator/health/**"
    };

    private final ObjectMapper objectMapper;

    public LoginInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        HttpSession session = request.getSession(false);
        Object user = session == null ? null : session.getAttribute(LoginGuard.SESSION_USER);
        if (user instanceof String name && !name.isBlank()) {
            return true;
        }

        String uri = request.getRequestURI();
        if (isApiRequest(request, uri)) {
            response.setHeader(AUTH_REQUIRED_HEADER, "1");
            JsonResponses.deny(response, objectMapper, 401, "未登录或登录已过期，请重新登录");
        } else {
            response.sendRedirect(LOGIN_PAGE + "?redirect=" + encodeTarget(request));
        }
        log.debug("拒绝 {} {}：未登录", request.getMethod(), uri);
        return false;
    }

    /**
     * 接口还是页面：看路径前缀，再看 {@code Accept}。
     *
     * <p>只看路径不够——{@code /v3/api-docs}、{@code /actuator} 也是「非页面」，
     * 但它们本来就该回 401/302 中的一种，回 401 更符合工具预期，所以这里只区分
     * {@code /api/**} 与显式要 JSON 的请求。</p>
     */
    private boolean isApiRequest(HttpServletRequest request, String uri) {
        if (uri.startsWith("/api/")) {
            return true;
        }
        if (uri.startsWith("/v3/api-docs") || uri.startsWith("/actuator")) {
            return true;
        }
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("application/json");
    }

    /**
     * 登录后要跳回的地址：只取本站路径，并做 URL 编码。
     *
     * <p>原样回填 {@code requestURI} 不会产生开放重定向（它必然以 {@code /} 开头），
     * 但查询串里可能有 {@code &}、中文、空格，必须编码后再拼进 {@code redirect} 参数；
     * 前端还会再校验一次「以单个 {@code /} 开头」，两道都要在。</p>
     */
    private String encodeTarget(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String query = request.getQueryString();
        String target = query == null || query.isBlank() ? uri : uri + "?" + query;
        return URLEncoder.encode(target, StandardCharsets.UTF_8);
    }
}
