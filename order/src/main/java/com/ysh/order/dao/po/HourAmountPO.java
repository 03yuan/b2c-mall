package com.ysh.order.dao.po;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 按小时聚合的结果行：小时（0~23）+ 该小时已支付金额合计
 */
@Data
public class HourAmountPO {

    private Integer hour;
    private BigDecimal amount;
}
