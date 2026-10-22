package org.example.fqxs.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class AdminTokenInterceptorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** /api/db/all 会把整表原样吐出去，GET 也一样要令牌。 */
    @Test
    void wholeTableReadsAreGuardedToo() throws Exception {
        AdminTokenInterceptor guard = new AdminTokenInterceptor("s3cret", mapper);

        MockHttpServletRequest noHeader = new MockHttpServletRequest("GET", "/api/db/all");
        MockHttpServletResponse denied = new MockHttpServletResponse();
        assertFalse(guard.preHandle(noHeader, denied, null));
        assertEquals(401, denied.getStatus());

        MockHttpServletRequest wrong = new MockHttpServletRequest("GET", "/api/db/all");
        wrong.addHeader(AdminTokenInterceptor.HEADER, "nope");
        assertFalse(guard.preHandle(wrong, new MockHttpServletResponse(), null));

        MockHttpServletRequest right = new MockHttpServletRequest("GET", "/api/db/all");
        right.addHeader(AdminTokenInterceptor.HEADER, "s3cret");
        assertTrue(guard.preHandle(right, new MockHttpServletResponse(), null));
    }

    /** 没配令牌时管理类接口整体关闭，而不是退化成“谁都能删”。 */
    @Test
    void disabledWhenNoTokenConfigured() throws Exception {
        AdminTokenInterceptor guard = new AdminTokenInterceptor("", mapper);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(guard.preHandle(new MockHttpServletRequest("DELETE", "/api/db/all"), response, null));
        assertEquals(403, response.getStatus());
    }
}
