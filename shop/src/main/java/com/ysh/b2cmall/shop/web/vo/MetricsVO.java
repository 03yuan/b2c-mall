package com.ysh.b2cmall.shop.web.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 4 项核心经营指标。值为 null 表示暂无数据（前端渲染「暂无数据」）。
 */
@Data
public class MetricsVO {

    /** 支付客户数（今日完成付款的独立客户数） */
    private Integer payCustomers;

    /** 支付订单数（今日成功支付订单总量） */
    private Integer payOrders;

    /** 退款金额（今日退款总金额） */
    private BigDecimal refundAmount;

    /** 客单价（今日总支付金额 ÷ 支付订单数） */
    private BigDecimal avgOrderValue;
}
