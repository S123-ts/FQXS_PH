package org.example.fqxs.web;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;

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
}
