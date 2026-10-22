package org.example.fqxs.model;

/**
 * 登录请求体：账号 + 口令。
 *
 * <p>刻意不加 {@code @Valid} 与长度约束：这里回给前端的永远是同一句
 * 「账号或口令错误」，把「口令太短」单独报出来等于告诉攻击者格式要求。
 * 空值与空白由 {@link org.example.fqxs.web.LoginGuard} 当成普通错误处理。</p>
 *
 * @param username 账号
 * @param password 口令（不 trim：首尾空格可能是口令的一部分）
 */
public record LoginRequest(String username, String password) {
}
