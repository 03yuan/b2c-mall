package com.ysh.order.statemachine;

import com.ysh.order.enums.OrderStatusEnum;

/**
 * 已付款状态：只能 ship()
 */
public class PaidState implements OrderState {

    @Override
    public OrderState pay() {
        throw new IllegalStateException("订单已付款，不能重复付款");
    }

    @Override
    public OrderState ship() {
        return new ShippedState();
    }

    @Override
    public OrderState confirm() {
        throw new IllegalStateException("未发货订单不能确认收货");
    }

    @Override
    public OrderStatusEnum getStatus() {
        return OrderStatusEnum.PAID;
    }
}
