package com.ysh.b2cmall.user.exception;

/**
 * 业务错误码统一包装：所有业务文案收敛到一处，防止魔法串漂移。
 * 防枚举契约：密码错误与用户名不存在必须永远共用 INVALID_CREDENTIALS。
 */
public enum ErrorCode {

    USERNAME_EXISTS("用户名已存在"),
    INVALID_CREDENTIALS("用户名或密码错误"),
    ACCOUNT_DISABLED("账号已禁用");

    private final String message;

    ErrorCode(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}