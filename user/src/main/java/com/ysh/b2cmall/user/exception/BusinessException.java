package com.ysh.b2cmall.user.exception;

/**
 * 用户服务统一业务异常包装：所有业务校验失败都抛本异常，
 * 由全局异常处理器统一转成 BaseResponseVO（status=400 + 中文 message）。
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}