package org.example.fqxs.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 前端已于 2026-09-24 重写为零内联：无内联 onclick、无内联 style、唯一脚本是
 * /js/app.js 这个 ES module 入口，因此 script-src 与 style-src 收紧到 'self'。
 * font-src 必须放行 https://lf6-awef.bytetos.com 与 https://lf3-awef.bytetos.com：
 * base.css 的 @font-face 从这两个域名加载反混淆字体，书名/作者/简介里的 PUA
 * 私用区字符靠它才能显示（否则渲染成 □）。旧配置 font-src 'self' 一直在拦
 * @font-face，属于隐性 bug，本次一并修复。
 * 仍然收住 default-src 与 connect-src，被注入的脚本无法再往外部主机传数据。
 * 图片必须放行 https：封面是被爬站点的签名 CDN 直链。
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final String CSP = csp(false);

    /** Swagger UI 的初始化脚本与样式是内联的，只在这几个文档路径上放宽。 */
    private static final String CSP_DOCS = csp(true);

    private static String csp(boolean allowInline) {
        String script = allowInline ? "script-src 'self' 'unsafe-inline'" : "script-src 'self'";
        String style = allowInline ? "style-src 'self' 'unsafe-inline'" : "style-src 'self'";
        return String.join("; ",
                "default-src 'self'",
                script,
                style,
                "img-src 'self' https: data:",
                "connect-src 'self'",
                "font-src 'self' https://lf6-awef.bytetos.com https://lf3-awef.bytetos.com",
                "form-action 'none'",
                "base-uri 'none'",
                "frame-ancestors 'none'");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        boolean docs = "/swagger-ui.html".equals(uri)
                || uri.startsWith("/swagger-ui/")
                || uri.startsWith("/v3/api-docs");
        response.setHeader("Content-Security-Policy", docs ? CSP_DOCS : CSP);
        response.setHeader("X-Content-Type-Options", "nosniff");
        // frame-ancestors 'none' 已覆盖现代浏览器，DENY 是给老浏览器与扫描器的双保险（报告 L3）
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "same-origin");
        // HSTS（报告 L2）：只在本次请求确实是 HTTPS 时下发。反代终止 TLS 时
        // request.isSecure() 是 false，靠反代带的 X-Forwarded-Proto 判断；
        // 不能无条件下发——本地 http 调试会被浏览器钉成 https，直接打不开
        if (request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"))) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000");
        }
        // 全站 no-store：API 会改写数据不该缓存；静态资源不缓存是为了让改版立即生效，
        // 否则浏览器启发式缓存 ES module，页面会验到旧副本（HANDOVER §8 的坑）
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
