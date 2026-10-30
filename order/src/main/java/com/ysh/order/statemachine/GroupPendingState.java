package com.ysh.order.statemachine;

import com.ysh.order.enums.OrderStatusEnum;

/**
 * 待成团状态：拼团功能尚未落地，暂无可达流转，所有操作均拒绝
 */
public class GroupPendingState implements OrderState {

    @Override
    public OrderState pay() {
        throw new IllegalStateException("待成团订单暂不支持付款");
    }

    @Override
    public OrderState ship() {
        throw new IllegalStateException("待成团订单不能发货");
    }

    @Override
    public OrderState confirm() {
        throw new IllegalStateException("待成团订单不能确认收货");
    }

    @Override
    public OrderState cancel() {
        throw new IllegalStateException("待成团订单暂不支持取消");
    }

    @Override
    public OrderStatusEnum getStatus() {
        return OrderStatusEnum.GROUP_PENDING;
    }
}
