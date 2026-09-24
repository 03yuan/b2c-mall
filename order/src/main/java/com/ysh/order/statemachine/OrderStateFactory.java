package com.ysh.order.statemachine;

import com.ysh.order.enums.OrderStatusEnum;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 状态工厂：根据DB中的status枚举值映射到对应的状态类实例
 */
@Component
public class OrderStateFactory {

    private static final Map<OrderStatusEnum, OrderState> STATE_MAP = Map.of(
            OrderStatusEnum.PENDING, new PendingState(),
            OrderStatusEnum.PAID, new PaidState(),
            OrderStatusEnum.SHIPPED, new ShippedState(),
            OrderStatusEnum.COMPLETED, new CompletedState()
    );

    /**
     * 根据状态枚举获取状态对象
     */
    public OrderState getState(OrderStatusEnum status) {
        OrderState state = STATE_MAP.get(status);
        if (state == null) {
            throw new IllegalArgumentException("未知订单状态: " + status);
        }
        return state;
    }

    /**
     * 根据状态字符串获取状态对象
     */
    public OrderState getState(String statusStr) {
        return getState(OrderStatusEnum.valueOf(statusStr));
    }
}
