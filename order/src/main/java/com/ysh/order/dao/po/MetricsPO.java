package com.ysh.order.dao.po;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 单日经营指标：支付客户数/支付订单数/退款金额/客单价
 */
@Data
public class MetricsPO {

    /** 支付客户数（今日 distinct 买家） */
    private Integer payCustomers;

    /** 支付订单数（今日成功支付订单总量） */
    private Integer payOrders;

    /** 今日退款总金额 */
    private BigDecimal refundAmount;

    /** 客单价 = 今日总支付金额 ÷ 支付订单数（0 单时为 null） */
    private BigDecimal avgOrderValue;
}
