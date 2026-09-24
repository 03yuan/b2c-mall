package com.ysh.order.statemachine;

import com.ysh.order.enums.OrderStatusEnum;

/**
 * 订单状态接口（状态模式）
 * 每个状态类实现此接口，只允许该状态下合法的操作，非法操作抛异常
 */
public interface OrderState {

    /** 付款：PENDING -> PAID */
    OrderState pay();

    /** 发货：PAID -> SHIPPED */
    OrderState ship();

    /** 确认收货：SHIPPED -> COMPLETED */
    OrderState confirm();

    /** 获取当前状态枚举（用于DB映射） */
    OrderStatusEnum getStatus();
}
