package com.ysh.order.web.vo;

import lombok.Data;

/**
 * 买家端订单状态计数结果（shop-order-list.md 功能上下文 10.1.2 / 10.2）。
 * 固定五个 key，无单状态返回 0 而非缺失；「全部」= 其余各状态之和（含 FAILED/GROUP_PENDING）。
 */
@Data
public class BuyerStatusCountsVO {

    /** 全部（含 FAILED/GROUP_PENDING 等所有状态） */
    private Integer all = 0;

    /** 待付款（PENDING） */
    private Integer pending = 0;

    /** 待发货（PAID） */
    private Integer paid = 0;

    /** 待收货（SHIPPED） */
    private Integer shipped = 0;

    /** 已完成（COMPLETED） */
    private Integer completed = 0;
}
