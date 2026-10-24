package org.example.fqxs.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.*;

/** 登录门：接口回 401（带标记头）、页面回 302，白名单之外一律挡住。 */
class LoginInterceptorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final LoginInterceptor interceptor = new LoginInterceptor(mapper);

    /** 书源端点在登录门白名单里：阅读 App 没有浏览器会话，靠可选令牌自保护。 */
    @Test
    void bookSourceEndpointsBypassTheSessionGate() {
        assertTrue(java.util.Arrays.asList(LoginInterceptor.WHITELIST).contains("/api/bs/**"),
                "Legado 书源端点必须在登录门白名单里");
    }

    @Test
    void allowsAuthenticatedSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/rank/meta");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(LoginGuard.SESSION_USER, "123");
        request.setSession(session);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), null));
    }

    /** 匿名接口请求：401 + X-Auth-Required，前端凭这个头跳登录页而不是弹「写令牌错误」。 */
    @Test
    void rejectsAnonymousApiWithMarkerHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/rank/meta");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, null));
        assertEquals(401, response.getStatus());
        assertEquals("1", response.getHeader(LoginInterceptor.AUTH_REQUIRED_HEADER));
        assertTrue(response.getContentAsString().contains("401"));
    }

    /** 匿名页面请求：302 到登录页，并带回原地址。 */
    @Test
    void redirectsAnonymousPageToLogin() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/index.html");
        request.setQueryString("tab=ai");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, null));
        assertEquals(302, response.getStatus());
        String target = response.getRedirectedUrl();
        assertNotNull(target);
        assertTrue(target.startsWith("/login?redirect="), target);
        // 原地址要编码后再拼进参数，否则查询串里的 & 会把参数截断
        assertTrue(target.contains("%2Findex.html%3Ftab%3Dai"), target);
    }

    /** 空会话（只有 JSESSIONID、没登录）不算已登录。 */
    @Test
    void emptySessionIsNotAuthenticated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/index.html");
        request.setSession(new MockHttpSession());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, null));
        assertEquals(302, response.getStatus());
    }

    /** 文档与 actuator 这类工具路径回 JSON 401，不回 302——否则 curl 拿到 HTML。 */
    @Test
    void treatsToolingPathsAsApi() throws Exception {
        MockHttpServletResponse docs = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(
                new MockHttpServletRequest("GET", "/v3/api-docs"), docs, null));
        assertEquals(401, docs.getStatus());

        MockHttpServletResponse actuator = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(
                new MockHttpServletRequest("GET", "/actuator/info"), actuator, null));
        assertEquals(401, actuator.getStatus());
    }

    /** 显式要 JSON 的非 /api 路径（例如自定义端点）也按接口处理。 */
    @Test
    void honoursAcceptHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/whatever");
        request.addHeader("Accept", "application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, null));
        assertEquals(401, response.getStatus());
    }

    /** 白名单必须真的包含登录页、登录接口与错误页，否则会自己把自己锁死。 */
    @Test
    void whitelistCoversLoginFlowAndErrorPage() {
        String[] whitelist = LoginInterceptor.WHITELIST;
        assertTrue(java.util.Arrays.asList(whitelist).contains("/login.html"));
        assertTrue(java.util.Arrays.asList(whitelist).contains("/api/auth/**"));
        assertTrue(java.util.Arrays.asList(whitelist).contains("/error"));
        assertTrue(java.util.Arrays.asList(whitelist).contains("/js/login.js"));
    }
}
