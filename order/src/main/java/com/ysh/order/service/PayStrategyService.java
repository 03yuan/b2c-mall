package com.ysh.order.service;

import java.math.BigDecimal;

/**
 * 支付策略接口（策略模式）
 * 不同支付方式实现此接口，通过 payType 选择策略
 */
public interface PayStrategyService {

    /**
     * 执行支付
     * @param orderNo 订单号
     * @param amount 支付金额
     * @return true=支付成功
     */
    boolean pay(String orderNo, BigDecimal amount);

    /**
     * 获取策略类型
     */
    String getPayType();
}
