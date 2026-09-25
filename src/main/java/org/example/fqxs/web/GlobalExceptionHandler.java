package org.example.fqxs.web;

import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.exception.CrawlException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Arrays;

/**
 * 抓取失败与数据库不可用此前被逐层吞成 code:0，前端无法区分“没数据”和“出错”。
 * 这里统一出口：错误响应带 code!=0 与可读 message。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CrawlException.class)
    public ResponseEntity<Object> onCrawl(CrawlException e) {
        log.error("抓取失败: {}", e.getMessage(), e);
        return body(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    /**
     * 连不上库时 Spring 先抛 TransactionException（事务开启阶段取不到连接），
     * 只有连上之后执行失败才是 DataAccessException，两类都要归到 503。
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<Object> onDb(RuntimeException e) {
        log.warn("数据库操作失败: {}", e.getMessage());
        return body(HttpStatus.SERVICE_UNAVAILABLE, "数据库不可用，请确认 data/ 下的库文件没被其他进程占用");
    }

    /** 参数不合法只回参数名，Spring 的原始消息里带 Java 类型名，不该透出给浏览器。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> onTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配: name={}, value={}", e.getName(), e.getValue());
        return body(HttpStatus.BAD_REQUEST, "参数 " + e.getName() + " 取值不合法");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Object> onMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数: {}", e.getParameterName());
        return body(HttpStatus.BAD_REQUEST, "缺少必填参数 " + e.getParameterName());
    }

    /** @Valid 请求体校验失败取第一条错误；原始消息带字段路径与 Java 类型，不该透出给浏览器。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Object> onValidationFailure(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> "参数 " + error.getField() + " " + error.getDefaultMessage())
                .orElse("请求体校验失败");
        log.warn("请求体校验失败: {}", message);
        return body(HttpStatus.BAD_REQUEST, message);
    }

    /** 请求体不是合法 JSON（或类型对不上）归为 400，不能落进兜底处理器报成 500。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> onUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("请求体不可读: {}", e.getMessage());
        return body(HttpStatus.BAD_REQUEST, "请求体不是合法的 JSON");
    }

    /** 兜底 handler 会先接走框架的标准异常，方法不匹配得单独留 405，否则 GET 抓取接口看起来像服务器故障。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Object> onMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("方法不被支持: {} 支持的方法: {}", e.getMethod(), Arrays.toString(e.getSupportedMethods()));
        return body(HttpStatus.METHOD_NOT_ALLOWED, e.getMethod() + " 不被支持，抓取接口请用 POST");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> onIllegalArgument(IllegalArgumentException e) {
        log.warn("请求被拒绝: {}", e.getMessage());
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 静态资源缺失不应被兜底处理器变成 500。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Object> onNotFound(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "资源不存在: " + e.getResourcePath());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> onOther(Exception e) {
        log.error("未处理异常", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误: " + e.getClass().getSimpleName());
    }

    private ResponseEntity<Object> body(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .header("Content-Type", "application/json;charset=UTF-8")
                .body(ApiResult.fail(status.value(), message));
    }
}
