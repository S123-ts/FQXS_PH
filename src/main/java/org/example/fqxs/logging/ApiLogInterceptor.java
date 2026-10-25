package org.example.fqxs.logging;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.util.DateUtil;
import org.example.fqxs.web.LoginGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 入站 API 日志：{@code /api/**} 每次请求留一行（{@code api_log.direction=IN}）。
 *
 * <p><b>必须注册在所有拦截器之前</b>：Spring 的规则是「preHandle 返回 false 之后，
 * 只回调已经成功执行过 preHandle 的拦截器的 afterCompletion」。登录门是第一个挡人的关卡，
 * 如果本类排在它后面，所有未登录的 401 都不会被记录——而「谁在扫接口」恰恰要看这些。</p>
 *
 * <p>本类只入队，不写库、不抛异常：它跑在请求线程上，任何抖动都会变成接口延迟。</p>
 */
@Slf4j
@Component
public class ApiLogInterceptor implements HandlerInterceptor {

    /** 记录起始时刻的属性名，afterCompletion 时取回算耗时。 */
    private static final String START_ATTR = "fqxs.apiLog.startNanos";

    /** 来源 IP 是否信 X-Forwarded-For：与登录失败限流同一开关，口径必须一致。 */
    private final boolean trustForwardedFor;

    public ApiLogInterceptor(@Value("${app.auth.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_ATTR, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception failure) {
        try {
            Object start = request.getAttribute(START_ATTR);
            long durationMs = start instanceof Long startNanos
                    ? (System.nanoTime() - startNanos) / 1_000_000L
                    : 0L;
            Integer status = response.getStatus();
            String query = request.getQueryString();
            LogBuffer.offerApi(new ApiLogEntry(
                    DateUtil.now(),
                    ApiLogEntry.DIRECTION_IN,
                    request.getMethod(),
                    AppLogEntry.truncate(request.getRequestURI(), ApiLogEntry.TARGET_MAX),
                    AppLogEntry.truncate(query, ApiLogEntry.QUERY_MAX),
                    bookIdOf(query),
                    status,
                    (int) Math.min(Integer.MAX_VALUE, durationMs),
                    null,
                    AppLogEntry.truncate(clientIpOf(request), ApiLogEntry.CLIENT_IP_MAX),
                    AppLogEntry.truncate(usernameOf(request), ApiLogEntry.USERNAME_MAX),
                    AppLogEntry.truncate(request.getHeader("User-Agent"), ApiLogEntry.USER_AGENT_MAX),
                    ApiLogEntry.outcomeOf(status, failure),
                    failure == null
                            ? null
                            : AppLogEntry.truncate(failure.getClass().getSimpleName() + ": " + failure.getMessage(),
                                    ApiLogEntry.ERROR_MAX)));
        } catch (RuntimeException ignored) {
            // 记日志失败不能影响接口本身
        }
    }

    /** 查询串里的 bookId / book_id：接口参数命名两种都有，统一认。 */
    static String bookIdOf(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = pair.substring(0, equals);
            if (!"bookId".equalsIgnoreCase(key) && !"book_id".equalsIgnoreCase(key)) {
                continue;
            }
            String value = URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            return value.isBlank() ? null : AppLogEntry.truncate(value, ApiLogEntry.BOOK_ID_MAX);
        }
        return null;
    }

    private String clientIpOf(HttpServletRequest request) {
        if (trustForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                // 反代可能链式追加，第一个才是客户端；且必须由反代覆盖，否则可伪造
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private String usernameOf(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object user = session == null ? null : session.getAttribute(LoginGuard.SESSION_USER);
        return user instanceof String name && !name.isBlank() ? name : null;
    }
}
