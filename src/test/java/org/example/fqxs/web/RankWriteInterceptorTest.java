package org.example.fqxs.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.*;

class RankWriteInterceptorTest {

    private final RankWriteInterceptor interceptor = new RankWriteInterceptor(new ObjectMapper());
    private final MockHttpSession session = new MockHttpSession();

    private MockHttpServletRequest request(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/rank");
        request.setSession(session);
        return request;
    }

    @Test
    void readsStayOpenButWritesNeedTheSessionToken() throws Exception {
        assertTrue(interceptor.preHandle(request("GET"), new MockHttpServletResponse(), null));

        MockHttpServletResponse denied = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("POST"), denied, null));
        assertEquals(401, denied.getStatus());

        MockHttpServletRequest granted = request("POST");
        granted.addHeader(RankWriteInterceptor.HEADER, RankWriteInterceptor.issueToken(session));
        assertTrue(interceptor.preHandle(granted, new MockHttpServletResponse(), null));
    }

    /** 令牌一会话一枚且稳定；别的会话自己生成的那枚不能用。 */
    @Test
    void tokenIsPerSessionAndStable() throws Exception {
        String token = RankWriteInterceptor.issueToken(session);
        assertEquals(token, RankWriteInterceptor.issueToken(session));
        assertNotEquals(token, RankWriteInterceptor.issueToken(new MockHttpSession()));

        MockHttpServletRequest forged = request("POST");
        forged.addHeader(RankWriteInterceptor.HEADER, RankWriteInterceptor.issueToken(new MockHttpSession()));
        assertFalse(interceptor.preHandle(forged, new MockHttpServletResponse(), null));
    }

    @Test
    void writeWithoutAnySessionIsRejected() throws Exception {
        MockHttpServletRequest anonymous = new MockHttpServletRequest("POST", "/api/rank/fetchAll");

        assertFalse(interceptor.preHandle(anonymous, new MockHttpServletResponse(), null));
    }
}
