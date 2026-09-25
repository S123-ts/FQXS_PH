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

    private static final String CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "img-src 'self' https: data:",
            "connect-src 'self'",
            "font-src 'self' https://lf6-awef.bytetos.com https://lf3-awef.bytetos.com",
            "form-action 'none'",
            "base-uri 'none'",
            "frame-ancestors 'none'");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CSP);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "same-origin");
        // 全站 no-store：API 会改写数据不该缓存；静态资源不缓存是为了让改版立即生效，
        // 否则浏览器启发式缓存 ES module，页面会验到旧副本（HANDOVER §8 的坑）
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
