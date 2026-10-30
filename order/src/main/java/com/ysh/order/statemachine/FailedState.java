package com.ysh.order.statemachine;

import com.ysh.order.enums.OrderStatusEnum;

/**
 * 交易失败状态（终态）：订单已取消或交易失败，所有流转均拒绝
 */
public class FailedState implements OrderState {

    @Override
    public OrderState pay() {
        throw new IllegalStateException("订单交易失败，不能付款");
    }

    @Override
    public OrderState ship() {
        throw new IllegalStateException("订单交易失败，不能发货");
    }

    @Override
    public OrderState confirm() {
        throw new IllegalStateException("订单交易失败，不能确认收货");
    }

    @Override
    public OrderState cancel() {
        throw new IllegalStateException("订单交易失败，不能重复取消");
    }

    @Override
    public OrderStatusEnum getStatus() {
        return OrderStatusEnum.FAILED;
    }
}
