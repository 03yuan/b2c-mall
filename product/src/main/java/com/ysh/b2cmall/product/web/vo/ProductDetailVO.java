package com.ysh.b2cmall.product.web.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 商品详情 VO（shop-product-list.md 功能上下文 7.1.1）：
 * 编辑页全部字段，含 images 图文、spec、配送方式、双库存、双销量、排序；不暴露 shopId。
 */
@Data
public class ProductDetailVO {

    private Integer skuId;
    private Integer categoryId;
    private String name;
    private String description;
    private BigDecimal price;
    private BigDecimal marketPrice;
    private Integer stock;
    private Integer storeStock;
    private Integer sales;
    private Integer fakeSales;
    private Integer type;
    private Integer productType;
    private String images;
    private String spec;
    private String deliveryMethods;
    private Integer sort;
    private Integer status;
}
