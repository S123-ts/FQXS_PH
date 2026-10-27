package org.example.fqxs.exception;

/**
 * 同一操作已在另一处进行中：请求本身没错，现在做不了，稍后重试即可。
 *
 * <p>与 500 区分开——前端要把「正在另一处校验」原样告诉用户，
 * 而不是显示「服务内部错误」。</p>
 */
public class OperationConflictException extends RuntimeException {

    public OperationConflictException(String message) {
        super(message);
    }
}
