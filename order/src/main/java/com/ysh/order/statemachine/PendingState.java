package com.ysh.order.statemachine;

import com.ysh.order.enums.OrderStatusEnum;

/**
 * 待付款状态：只能 pay()
 */
public class PendingState implements OrderState {

    @Override
    public OrderState pay() {
        return new PaidState();
    }

    @Override
    public OrderState ship() {
        throw new IllegalStateException("待付款订单不能发货");
    }

    @Override
    public OrderState confirm() {
        throw new IllegalStateException("待付款订单不能确认收货");
    }

    @Override
    public OrderStatusEnum getStatus() {
        return OrderStatusEnum.PENDING;
    }
}
