package com.ysh.order.statemachine;

import com.ysh.order.enums.OrderStatusEnum;

/**
 * 已发货状态：只能 confirm()
 */
public class ShippedState implements OrderState {

    @Override
    public OrderState pay() {
        throw new IllegalStateException("订单已发货，不能付款");
    }

    @Override
    public OrderState ship() {
        throw new IllegalStateException("订单已发货，不能重复发货");
    }

    @Override
    public OrderState confirm() {
        return new CompletedState();
    }

    @Override
    public OrderStatusEnum getStatus() {
        return OrderStatusEnum.SHIPPED;
    }
}
