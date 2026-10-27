package org.example.fqxs.web;

import org.apache.catalina.connector.ClientAbortException;
import org.example.fqxs.exception.OperationConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库连不上时 Spring 在事务开启阶段抛 CannotCreateTransactionException，
 * 它不是 DataAccessException 的子类，早退会被兜底处理器报成 500 + Java 类名。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void bothDatabaseFailureShapesReportServiceUnavailable() {
        ResponseEntity<Object> tx = handler.onDb(
                new CannotCreateTransactionException("Could not open JDBC Connection", null));
        ResponseEntity<Object> dao = handler.onDb(
                new DataAccessResourceFailureException("数据库未启动"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, tx.getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, dao.getStatusCode());
        assertTrue(String.valueOf(tx.getBody()).contains("数据库不可用"));
    }

    /**
     * 唯一键冲突是并发写入，不是库挂了：回 503「库文件被占用」会把排查方向带偏。
     */
    @Test
    void uniqueKeyConflictIsReportedAsConflictNotDatabaseDown() {
        ResponseEntity<Object> response = handler.onDataConflict(
                new DataIntegrityViolationException("Duplicate entry for key (book_id, engine, model)"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        String body = String.valueOf(response.getBody());
        assertTrue(body.contains("另一处写入"), body);
        assertFalse(body.contains("库文件"), body);
    }

    /** 逐章校验已在另一处推进：原样把「稍后再点」告诉用户，不能落成服务内部错误。 */
    @Test
    void concurrentVerifyIsReportedAsConflict() {
        ResponseEntity<Object> response = handler.onOperationConflict(
                new OperationConflictException("这本书的这个引擎正在另一处逐章校验，等它结束再点"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertTrue(String.valueOf(response.getBody()).contains("正在另一处逐章校验"));
    }

    /**
     * 客户端断连是连接层事件，不能再尝试写响应（会二次失败），
     * 也不该落到兜底分支记 ERROR。
     */
    @Test
    void clientDisconnectIsHandledWithoutResponse() {
        assertDoesNotThrow(() -> handler.onClientAbort(
                new ClientAbortException(new IOException("Connection reset by peer"))));
        assertDoesNotThrow(() -> handler.onClientAbort(
                new AsyncRequestNotUsableException("ServletOutputStream failed to write")));
    }

    /**
     * 兜底分支不能把 Java 类名回给浏览器：异常类型是内部实现细节，
     * 日志里有完整栈，前端只需要一句能照着做的提示。
     */
    @Test
    void unknownExceptionDoesNotLeakJavaClassName() {
        ResponseEntity<Object> response = handler.onOther(
                new IllegalStateException("内部状态错乱: com.example.SecretService"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        String body = String.valueOf(response.getBody());
        assertTrue(body.contains("服务内部错误"), body);
        // 类名、包名、原始异常消息都不该出现在回包里
        assertFalse(body.contains("IllegalStateException"), body);
        assertFalse(body.contains("com.example"), body);
    }
}
