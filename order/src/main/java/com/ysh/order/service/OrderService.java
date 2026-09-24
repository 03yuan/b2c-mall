package com.ysh.order.service;

import com.ysh.order.dao.po.OrderPO;

import java.util.Map;

public interface OrderService {

    /** 下单 */
    Map<String, Object> createOrder(Integer shopId, Integer employeeId,
                                     Integer skuId, String skuName,
                                     Integer quantity, java.math.BigDecimal price,
                                     String receiverName, String receiverPhone, String receiverAddress);

    /** 支付（策略模式选支付方式，状态模式流转） */
    void pay(Integer orderId, String payType, Integer operatorId);

    /** 发货（状态模式流转） */
    void ship(Integer orderId, Integer operatorId);

    /** 确认收货（状态模式流转） */
    void confirm(Integer orderId, Integer operatorId);

    /** 查询订单 */
    OrderPO getOrder(Integer orderId);
}
