package org.example.fqxs.logging;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 入站 API 日志的契约：bookId 解析、状态码映射、未登录也留痕。 */
class ApiLogInterceptorTest {

    private ApiLogInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new ApiLogInterceptor(false);
        List<ApiLogEntry> sink = new ArrayList<>();
        while (LogBuffer.drainApi(sink, 1000) > 0) {
            sink.clear();
        }
    }

    @Test
    void bookIdIsReadFromCamelAndSnakeCase() {
        assertEquals("7123456789", ApiLogInterceptor.bookIdOf("bookId=7123456789&engine=glm"));
        assertEquals("7123456789", ApiLogInterceptor.bookIdOf("engine=glm&book_id=7123456789"));
        assertNull(ApiLogInterceptor.bookIdOf("engine=glm"));
        assertNull(ApiLogInterceptor.bookIdOf(null));
        assertNull(ApiLogInterceptor.bookIdOf("bookId="));
    }

    @Test
    void recordsStatusDurationAndBookId() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ai/detect");
        request.setQueryString("bookId=42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        List<ApiLogEntry> rows = drain();
        assertEquals(1, rows.size());
        ApiLogEntry row = rows.get(0);
        assertEquals(ApiLogEntry.DIRECTION_IN, row.direction());
        assertEquals("/api/ai/detect", row.target());
        assertEquals("42", row.bookId());
        assertEquals(200, row.status());
        assertEquals(ApiLogEntry.OUTCOME_OK, row.outcome());
    }

    /** 登录门挡下的请求也必须留一行：否则「谁在扫接口」完全看不见。 */
    @Test
    void deniedRequestsAreMarkedDenied() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/rank/crawl");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        ApiLogEntry row = drain().get(0);
        assertEquals(ApiLogEntry.OUTCOME_DENIED, row.outcome());
        assertEquals(401, row.status());
    }

    @Test
    void exceptionMarksRowAsError() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ai/status");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(500);

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(),
                new IllegalStateException("数据库不可用"));

        ApiLogEntry row = drain().get(0);
        assertEquals(ApiLogEntry.OUTCOME_ERROR, row.outcome());
        assertTrue(row.error().contains("数据库不可用"));
    }

    @Test
    void longUserAgentIsTruncated() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ai/status");
        request.addHeader("User-Agent", "u".repeat(ApiLogEntry.USER_AGENT_MAX + 50));
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        assertEquals(ApiLogEntry.USER_AGENT_MAX, drain().get(0).userAgent().length());
    }

    private List<ApiLogEntry> drain() {
        List<ApiLogEntry> rows = new ArrayList<>();
        LogBuffer.drainApi(rows, 100);
        return rows;
    }
}
