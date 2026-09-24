package com.ysh.order.statemachine;

import com.ysh.order.enums.OrderStatusEnum;

/**
 * 已完成状态：所有操作都拒绝
 */
public class CompletedState implements OrderState {

    @Override
    public OrderState pay() {
        throw new IllegalStateException("订单已完成");
    }

    @Override
    public OrderState ship() {
        throw new IllegalStateException("订单已完成");
    }

    @Override
    public OrderState confirm() {
        throw new IllegalStateException("订单已完成");
    }

    @Override
    public OrderStatusEnum getStatus() {
        return OrderStatusEnum.COMPLETED;
    }
}
