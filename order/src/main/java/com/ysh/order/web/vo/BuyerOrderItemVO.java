package com.ysh.order.web.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 买家订单卡片商品行（shop-order-list.md 功能上下文 10.2.1）。
 * 卡片商品行不含 isGift（赠品标记为商家端列表口径）。
 */
@Data
public class BuyerOrderItemVO {

    private String skuImage;

    private String skuName;

    private Integer quantity;

    private BigDecimal unitPrice;
}
