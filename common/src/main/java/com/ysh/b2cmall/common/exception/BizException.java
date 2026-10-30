package com.ysh.b2cmall.common.exception;

/**
 * 统一业务异常。
 * 携带 HTTP 状态码（int）+ 中文提示，供各服务 GlobalExceptionHandler 统一包装，
 * 取代「散落的 IllegalArgumentException + 消息文本猜状态码」的旧模式。
 *
 * 不直接依赖 spring-web 的 HttpStatus，保持 common 轻量；服务端用 HttpStatus.valueOf(code) 转换。
 *
 * 用法示例：
 * <pre>
 *   throw BizException.badRequest("价格必须大于0");
 *   throw BizException.notFound("订单不存在: " + orderId);
 * </pre>
 */
public class BizException extends RuntimeException {

    /** 400 - 业务规则不满足 / 参数错误 */
    public static final int BAD_REQUEST = 400;
    /** 404 - 资源不存在 */
    public static final int NOT_FOUND = 404;
    /** 503 - 下游服务不可用 */
    public static final int SERVICE_UNAVAILABLE = 503;

    /** 对应 HTTP 状态码 */
    private final int status;

    public BizException(int status, String message) {
        super(message);
        this.status = status;
    }

    public BizException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /** 业务规则不满足 / 参数错误 -> 400 */
    public static BizException badRequest(String message) {
        return new BizException(BAD_REQUEST, message);
    }

    /** 资源不存在 -> 404 */
    public static BizException notFound(String message) {
        return new BizException(NOT_FOUND, message);
    }

    /** 下游服务不可用 -> 503 */
    public static BizException serviceUnavailable(String message) {
        return new BizException(SERVICE_UNAVAILABLE, message);
    }

    public int getStatus() {
        return status;
    }
}
