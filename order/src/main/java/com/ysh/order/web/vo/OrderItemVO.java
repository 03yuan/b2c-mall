package com.ysh.order.web.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单行商品信息（shop-order-list.md 功能上下文 6.1.1）。
 * 当前一单一商品恒 1 条，用数组结构向前兼容多商品。
 */
@Data
public class OrderItemVO {

    private String skuImage;

    private String skuName;

    private BigDecimal unitPrice;

    private Integer quantity;

    /** 1=赠品，0=非赠品 */
    private Integer isGift;
}
