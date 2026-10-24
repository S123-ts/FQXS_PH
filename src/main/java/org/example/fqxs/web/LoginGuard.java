package org.example.fqxs.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录凭据校验 + 失败限流。
 *
 * <p>背景：写操作此前只有防 CSRF 的会话令牌，没有防未授权的身份认证。
 * {@code X-Rank-Token} 只能挡「其他站点冒用浏览器会话」，挡不住「直接连上来的请求」。
 * 本类提供后者的判定——账号口令比对，外加按来源 IP 的失败次数限流。</p>
 *
 * <p>三条纪律：</p>
 * <ol>
 *   <li><b>凭据只从配置读</b>：{@code app.auth.username} / {@code app.auth.password}，
 *       可用 {@code APP_LOGIN_USER} / {@code APP_LOGIN_PASSWORD} 环境变量覆盖，代码里不再出现第二份。</li>
 *   <li><b>比对用 {@link MessageDigest#isEqual}</b>：与 {@code AdminTokenInterceptor} 同一套，
 *       避免按字节短路造成的时序侧信道；账号与口令两段都会算完再合并，不做「账号错就提前返回」。</li>
 *   <li><b>失败不区分账号还是口令</b>：对外只有一句「账号或口令错误」，不给枚举账号的反馈。</li>
 * </ol>
 *
 * <p>限流状态只在内存里：单进程单实例（H2 文件库本身就只允许一个进程），重启即清空，
 * 不需要也不应该落库。</p>
 */
@Slf4j
@Component
public class LoginGuard {

    /** 会话里保存登录账号的属性名：拦截器、登出接口与 /api/auth/me 共用这一处。 */
    public static final String SESSION_USER = "fqxs.auth.user";

    /** 窗口内允许的连续失败次数。 */
    static final int MAX_FAILURES = 5;

    /** 失败计数窗口：超过这个时长没再失败，计数从头开始。 */
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(10);

    /** 触发上限后的锁定时长。 */
    static final Duration LOCK_DURATION = Duration.ofMinutes(5);

    /**
     * 限流表的容量上限。达到上限先清一遍过期项；仍然超标才整体丢弃——
     * 这是「宁可重置计数也不让内存被伪造来源刷爆」的取舍，正常单机部署碰不到。
     */
    private static final int MAX_TRACKED_SOURCES = 512;

    /** 登录结果：控制器按它映射 HTTP 状态码，不在这里碰响应。 */
    public enum Result {
        /** 账号口令正确。 */
        OK,
        /** 账号或口令错误（不区分是哪一项）。 */
        BAD_CREDENTIALS,
        /** 失败次数超限，处于锁定期。 */
        LOCKED,
        /** 没配置登录凭据，登录功能整体关闭（fail-closed）。 */
        DISABLED
    }

    /** 某个来源的失败计数；不可变，更新走 {@code compute}，避免并发下读到半更新状态。 */
    private record Attempt(int failures, long windowStart, long lockedUntil) {
    }

    private final byte[] expectedUser;
    private final byte[] expectedPassword;
    private final boolean trustForwardedFor;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    public LoginGuard(@Value("${app.auth.username:}") String username,
                      @Value("${app.auth.password:}") String password,
                      @Value("${app.auth.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.expectedUser = bytesOf(username == null ? "" : username.trim());
        this.expectedPassword = bytesOf(password == null ? "" : password);
        this.trustForwardedFor = trustForwardedFor;
        if (!enabled()) {
            // fail-closed：没有凭据时一律登不进来，而不是「谁都能进」
            log.warn("未配置登录凭据（app.auth.username / app.auth.password）：所有请求都会被登录拦截器拒绝");
        }
    }

    /** 是否配了可用的登录凭据；没配时登录接口回 403，全站仍然锁死。 */
    public boolean enabled() {
        return expectedUser.length > 0 && expectedPassword.length > 0;
    }

    /**
     * 校验一次登录尝试。
     *
     * @param source   来源标识（通常取 {@link #sourceOf(HttpServletRequest)}），限流按它分组
     * @param username 提交的账号，会 trim
     * @param password 提交的口令，<b>不</b> trim（首尾空格可能是口令的一部分）
     * @return 校验结果
     */
    public Result authenticate(String source, String username, String password) {
        if (!enabled()) {
            return Result.DISABLED;
        }
        long now = System.currentTimeMillis();
        Attempt current = attempts.get(source);
        if (current != null && current.lockedUntil() > now) {
            // 锁定期内直接拒，不再比对：连「这次口令对不对」都不透露
            return Result.LOCKED;
        }
        // 两段都算完再合并：短路会让「账号错」比「口令错」快，等于把账号是否存在泄漏出去
        boolean userOk = matches(expectedUser, username == null ? "" : username.trim());
        boolean passwordOk = matches(expectedPassword, password == null ? "" : password);
        if (userOk && passwordOk) {
            attempts.remove(source);
            return Result.OK;
        }
        Attempt after = registerFailure(source, now);
        return after.lockedUntil() > now ? Result.LOCKED : Result.BAD_CREDENTIALS;
    }

    /** 该来源还需锁多少秒（0 = 没锁），用于 429 的 {@code Retry-After}。 */
    public long lockRemainingSeconds(String source) {
        Attempt attempt = attempts.get(source);
        if (attempt == null) {
            return 0L;
        }
        long remaining = attempt.lockedUntil() - System.currentTimeMillis();
        return remaining <= 0 ? 0L : (remaining + 999) / 1000;
    }

    /**
     * 取来源标识。默认只认 {@code remoteAddr}。
     *
     * <p>放在反代后面时所有人都是 {@code 127.0.0.1}，按 IP 限流会退化成「一次锁定全站」，
     * 因此需要 {@code app.auth.trust-forwarded-for=true}；开启后必须让反代<b>覆盖</b>该头
     * （Nginx：{@code proxy_set_header X-Forwarded-For $remote_addr;}），
     * 否则客户端可以自己伪造来源绕过限流。</p>
     */
    public String sourceOf(HttpServletRequest request) {
        if (trustForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                String first = comma > 0 ? forwarded.substring(0, comma) : forwarded;
                if (!first.isBlank()) {
                    return first.trim();
                }
            }
        }
        return request.getRemoteAddr();
    }

    /** 清空限流状态；单测用，运行时不调用。 */
    void resetAttempts() {
        attempts.clear();
    }

    private Attempt registerFailure(String source, long now) {
        if (attempts.size() >= MAX_TRACKED_SOURCES) {
            purgeExpired(now);
        }
        return attempts.compute(source, (key, previous) -> {
            if (previous == null || now - previous.windowStart() > FAILURE_WINDOW.toMillis()) {
                return new Attempt(1, now, 0L);
            }
            int failures = previous.failures() + 1;
            long lockedUntil = failures >= MAX_FAILURES ? now + LOCK_DURATION.toMillis() : 0L;
            if (lockedUntil > 0) {
                log.warn("登录失败次数超限，来源 {} 锁定 {} 分钟", source, LOCK_DURATION.toMinutes());
            }
            return new Attempt(failures, previous.windowStart(), lockedUntil);
        });
    }

    /** 清掉窗口与锁定都已过期的条目；清完仍然超标就整体丢弃（容量兜底，见类注释）。 */
    private void purgeExpired(long now) {
        Iterator<Map.Entry<String, Attempt>> it = attempts.entrySet().iterator();
        while (it.hasNext()) {
            Attempt attempt = it.next().getValue();
            long expiresAt = Math.max(attempt.windowStart() + FAILURE_WINDOW.toMillis(), attempt.lockedUntil());
            if (expiresAt < now) {
                it.remove();
            }
        }
        if (attempts.size() >= MAX_TRACKED_SOURCES) {
            log.warn("登录限流表达到容量上限（{}），整体重置", MAX_TRACKED_SOURCES);
            attempts.clear();
        }
    }

    private static boolean matches(byte[] expected, String provided) {
        return MessageDigest.isEqual(expected, bytesOf(provided));
    }

    private static byte[] bytesOf(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
