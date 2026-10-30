package com.ysh.b2cmall.shop.web.vo;

import lombok.Data;

/**
 * 顶部 6 个订单状态统计卡片。值为 null 表示暂无数据（前端渲染「暂无数据」）。
 */
@Data
public class CardsVO {

    /** 待付款（PENDING） */
    private Integer pendingPayment;

    /** 待发货（PAID） */
    private Integer pendingShipment;

    /** 待收货（SHIPPED） */
    private Integer pendingReceipt;

    /** 已完成（COMPLETED） */
    private Integer completed;

    /** 退款/售后（进行中的退款申请单据数） */
    private Integer refundAfterSale;

    /** 库存预警（库存低于自身阈值的商品数） */
    private Integer stockAlert;
}
