package com.ysh.b2cmall.user.exception;

import com.ysh.b2cmall.common.exception.BizException;

/**
 * 用户服务统一业务异常包装：所有业务校验失败都抛本异常，
 * 由全局异常处理器统一转成 BaseResponseVO（status=400 + 中文 message）。
 *
 * 继承 common.BizException，收敛到统一异常体系（状态码由 BizException 携带，默认 400）。
 * 保留本类是为了兼容既有 catch(BusinessException) 的契约测试，避免大面积改动调用方。
 */
public class BusinessException extends BizException {

    public BusinessException(String message) {
        super(BizException.BAD_REQUEST, message);
    }
}
