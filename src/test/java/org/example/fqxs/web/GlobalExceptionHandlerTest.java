package org.example.fqxs.web;

import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
