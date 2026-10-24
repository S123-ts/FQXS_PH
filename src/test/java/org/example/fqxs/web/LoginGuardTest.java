package org.example.fqxs.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

/** 登录凭据校验与失败限流：报告 H1 的判定核心，M1 的限流兜底。 */
class LoginGuardTest {

    private static LoginGuard guard() {
        return new LoginGuard("admin", "admin", false);
    }

    @Test
    void acceptsConfiguredCredentials() {
        LoginGuard guard = guard();
        assertEquals(LoginGuard.Result.OK, guard.authenticate("1.1.1.1", "admin", "admin"));
    }

    /** 账号两边带空格是复制粘贴的常见形态，账号 trim；口令不 trim（空格可能是口令的一部分）。 */
    @Test
    void trimsUsernameButNotPassword() {
        LoginGuard guard = guard();
        assertEquals(LoginGuard.Result.OK, guard.authenticate("1.1.1.1", "  admin ", "admin"));
        assertEquals(LoginGuard.Result.BAD_CREDENTIALS,
                guard.authenticate("2.2.2.2", "admin", " admin"));
    }

    @Test
    void rejectsWrongUsernameOrPassword() {
        LoginGuard guard = guard();
        assertEquals(LoginGuard.Result.BAD_CREDENTIALS, guard.authenticate("1.1.1.1", "124", "admin"));
        assertEquals(LoginGuard.Result.BAD_CREDENTIALS, guard.authenticate("1.1.1.1", "admin", "admin2"));
        assertEquals(LoginGuard.Result.BAD_CREDENTIALS, guard.authenticate("1.1.1.1", null, null));
        assertEquals(LoginGuard.Result.BAD_CREDENTIALS, guard.authenticate("1.1.1.1", "", ""));
    }

    /** 没配凭据时整体关闭（fail-closed），而不是「空口令就能进」。 */
    @Test
    void disabledWithoutConfiguredCredentials() {
        LoginGuard guard = new LoginGuard("", "", false);
        assertFalse(guard.enabled());
        assertEquals(LoginGuard.Result.DISABLED, guard.authenticate("1.1.1.1", "", ""));
        assertEquals(LoginGuard.Result.DISABLED, guard.authenticate("1.1.1.1", "admin", "admin"));
    }

    /** 同一来源连续失败到上限就锁定；锁定期内连正确口令也拒（不透露「这次对了」）。 */
    @Test
    void locksSourceAfterRepeatedFailures() {
        LoginGuard guard = guard();
        for (int i = 0; i < LoginGuard.MAX_FAILURES - 1; i++) {
            assertEquals(LoginGuard.Result.BAD_CREDENTIALS, guard.authenticate("9.9.9.9", "admin", "bad"));
        }
        assertEquals(LoginGuard.Result.LOCKED, guard.authenticate("9.9.9.9", "admin", "bad"));
        assertEquals(LoginGuard.Result.LOCKED, guard.authenticate("9.9.9.9", "admin", "admin"));
        assertTrue(guard.lockRemainingSeconds("9.9.9.9") > 0);
    }

    /** 锁定按来源分组：A 被打满不影响 B（否则一个攻击者能把唯一的用户锁在门外）。 */
    @Test
    void lockIsPerSource() {
        LoginGuard guard = guard();
        for (int i = 0; i < LoginGuard.MAX_FAILURES; i++) {
            guard.authenticate("9.9.9.9", "admin", "bad");
        }
        assertEquals(LoginGuard.Result.OK, guard.authenticate("8.8.8.8", "admin", "admin"));
    }

    /** 登录成功要把失败计数清零，否则「偶尔输错几次」会累积到锁定。 */
    @Test
    void successResetsFailureCounter() {
        LoginGuard guard = guard();
        for (int i = 0; i < LoginGuard.MAX_FAILURES - 1; i++) {
            guard.authenticate("7.7.7.7", "admin", "bad");
        }
        assertEquals(LoginGuard.Result.OK, guard.authenticate("7.7.7.7", "admin", "admin"));
        for (int i = 0; i < LoginGuard.MAX_FAILURES - 1; i++) {
            assertEquals(LoginGuard.Result.BAD_CREDENTIALS, guard.authenticate("7.7.7.7", "admin", "bad"));
        }
    }

    /** 默认不信任 X-Forwarded-For：否则客户端自己写个头就能绕过限流。 */
    @Test
    void ignoresForwardedHeaderByDefault() {
        LoginGuard guard = guard();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        assertEquals("10.0.0.1", guard.sourceOf(request));
    }

    /** 反代后面所有人的 remoteAddr 都是 127.0.0.1，只能靠反代覆盖的头区分来源。 */
    @Test
    void usesFirstForwardedHopWhenTrusted() {
        LoginGuard guard = new LoginGuard("admin", "admin", true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");
        assertEquals("203.0.113.9", guard.sourceOf(request));
    }
}
