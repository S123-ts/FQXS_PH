package org.example.fqxs.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.model.LoginRequest;
import org.example.fqxs.web.ApiResponse;
import org.example.fqxs.web.LoginGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录 / 登出 / 当前身份（安全测试报告 H1 的入口）。
 *
 * <p>本类是 {@link org.example.fqxs.web.LoginInterceptor} 白名单里唯一的
 * {@code /api/**} 前缀——否则未登录的人连登录接口都调不到。</p>
 */
@Slf4j
@Tag(name = "登录")
@RestController
@RequestMapping(value = "/api/auth", produces = MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
@RequiredArgsConstructor
public class AuthController {

    private final LoginGuard loginGuard;

    /**
     * 校验账号口令并建立会话。
     *
     * <p>成功后<b>先作废旧会话再新建</b>（防会话固定）：攻击者预先塞给受害者一个
     * 已知的 JSESSIONID，受害者登录后即可用该 id 直接进入后台——
     * 更换 id 后这条链路断开。新会话只放账号名，不放口令与 Key。</p>
     *
     * @param body    账号与口令
     * @param request 取来源 IP 做失败限流
     * @return 200 登录成功 / 401 凭据错误 / 429 失败过多被锁 / 403 未配置凭据
     */
    @Operation(summary = "登录")
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(@RequestBody LoginRequest body,
                                                                  HttpServletRequest request) {
        String source = loginGuard.sourceOf(request);
        String username = body.username();
        String password = body.password();

        LoginGuard.Result result = loginGuard.authenticate(source, username, password);
        switch (result) {
            case OK -> {
                HttpSession previous = request.getSession(false);
                if (previous != null) {
                    previous.invalidate();
                }
                String name = username.trim();
                HttpSession session = request.getSession(true);
                session.setAttribute(LoginGuard.SESSION_USER, name);
                log.info("登录成功：来源 {}", source);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("user", name);
                return ResponseEntity.ok(ApiResponse.data("登录成功", data));
            }
            case DISABLED -> {
                log.warn("登录被拒：未配置 app.auth.username / app.auth.password");
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(new ApiResponse<>(HttpStatus.FORBIDDEN.value(), "未配置登录凭据，登录功能已关闭", null, null));
            }
            case LOCKED -> {
                long retryAfter = Math.max(1L, loginGuard.lockRemainingSeconds(source));
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", String.valueOf(retryAfter))
                        .body(new ApiResponse<>(HttpStatus.TOO_MANY_REQUESTS.value(),
                                "登录失败次数过多，请 " + retryAfter + " 秒后再试", null, null));
            }
            default -> {
                // 只记来源与结果，账号口令一律不进日志（与密钥同一条纪律）
                log.warn("登录失败：来源 {}", source);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new ApiResponse<>(HttpStatus.UNAUTHORIZED.value(), "账号或口令错误", null, null));
            }
        }
    }

    /** 退出登录：销毁会话。未登录时调用也返回成功（幂等），前端不必先判断状态。 */
    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ApiResponse.ok("已退出登录");
    }

    /**
     * 当前登录状态：登录页用它判断「已经登录过就不要再显示表单」。
     *
     * <p>不建会话（{@code getSession(false)}）：匿名访问不该在服务端堆出空会话。</p>
     */
    @Operation(summary = "当前登录状态")
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object user = session == null ? null : session.getAttribute(LoginGuard.SESSION_USER);
        boolean authenticated = user instanceof String name && !name.isBlank();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("authenticated", authenticated);
        if (authenticated) {
            data.put("user", user);
        }
        return ApiResponse.data(authenticated ? "已登录" : "未登录", data);
    }
}
