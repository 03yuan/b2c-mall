package com.ysh.b2cmall.product.service;

/**
 * 下游服务不可用异常（shop-product-list.md 功能上下文 7.3.4）。
 * 语义：跨服务调用失败/响应契约破坏时抛出，映射 HTTP 503；
 * 删除等关键操作宁可拒绝也不放行（可用性换正确性）。
 */
public class DownstreamServiceException extends RuntimeException {

    public DownstreamServiceException(String message) {
        super(message);
    }

    public DownstreamServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
