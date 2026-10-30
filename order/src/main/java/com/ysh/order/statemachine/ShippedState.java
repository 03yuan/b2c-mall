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
    public OrderState cancel() {
        throw new IllegalStateException("订单已发货，请申请退款，不能取消");
    }

    @Override
    public OrderStatusEnum getStatus() {
        return OrderStatusEnum.SHIPPED;
    }
}
