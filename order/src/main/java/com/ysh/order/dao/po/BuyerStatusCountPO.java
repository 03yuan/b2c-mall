package com.ysh.order.dao.po;

import lombok.Data;

/**
 * 买家端订单状态计数（shop-order-list.md 功能上下文 10.1.2）。
 * GROUP BY status 的原始聚合行：status -> count，排除 buyer_deleted=1。
 * 固定五个 key（全部/待付款/待发货/待收货/已完成）的归一化在 Service 层完成。
 */
@Data
public class BuyerStatusCountPO {

    /** 订单状态原始枚举值 */
    private String status;

    /** 该状态订单数 */
    private Integer count;
}
