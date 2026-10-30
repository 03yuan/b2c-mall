package com.ysh.b2cmall.product.web.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 商品列表行 VO（shop-product-list.md 功能上下文 3.2.1）：
 * 每行含缩略图（images 第一张图，非整串）、商品名称、市场价、销售价、商品销量、注水销量、
 * 仓库库存、门店库存、商品类型、商品类别、状态、配送方式、排序、skuId。
 */
@Data
public class ProductItemVO {

    private Integer skuId;

    /** 缩略图（images 第一张图，非整串） */
    private String thumbnail;

    /** 商品名称 */
    private String name;

    /** 市场价（划线原价） */
    private BigDecimal marketPrice;

    /** 销售价 */
    private BigDecimal price;

    /** 商品销量（真实成交） */
    private Integer sales;

    /** 注水销量 */
    private Integer fakeSales;

    /** 仓库库存 */
    private Integer stock;

    /** 门店库存 */
    private Integer storeStock;

    /** 商品类型 1-普通商品 2-秒杀商品 */
    private Integer productType;

    /** 商品类别 1-实物 2-虚拟 3-卡券 */
    private Integer type;

    /** 状态 0-下架 1-上架 */
    private Integer status;

    /** 配送方式（逗号分隔：CITY/PICKUP/EXPRESS） */
    private String deliveryMethods;

    /** 排序（数字越大前台越靠前） */
    private Integer sort;
}
