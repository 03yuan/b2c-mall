package com.ysh.b2cmall.product.web.request;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 商品更新请求体（shop-product-list.md 功能上下文 7.1.2）：
 * 基础信息/价格/库存/图文/配送/排序/类型/类别/注水销量；sales 不在请求体内（真实销量不可改）。
 */
@Data
public class UpdateProductRequest {

    /** 商品ID（必填） */
    private Integer skuId;

    private Integer categoryId;
    private String name;
    private String description;
    private BigDecimal price;
    private BigDecimal marketPrice;
    private Integer stock;
    private Integer storeStock;
    private Integer fakeSales;
    private Integer type;
    private Integer productType;
    private String images;
    private String spec;
    private String deliveryMethods;
    private Integer sort;
    private Integer status;
}
