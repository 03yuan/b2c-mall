package com.ysh.order.enums;

/**
 * 订单状态枚举（存入DB的status字段）
 */
public enum OrderStatusEnum {
    PENDING,     // 待付款
    PAID,        // 已付款
    SHIPPED,     // 已发货
    COMPLETED    // 已签收
}
