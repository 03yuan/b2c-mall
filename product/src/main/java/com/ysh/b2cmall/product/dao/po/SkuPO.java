package com.ysh.b2cmall.product.dao.po;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 商品 SKU 实体
 */
@Data
public class SkuPO {

    private Integer id;
    private Integer shopId;          // 所属店铺
    private Integer categoryId;      // 类目ID
    private String name;             // 商品名称
    private String description;      // 商品描述
    private BigDecimal price;        // 价格
    private Integer stock;           // 库存（虚拟商品可为空）
    private Integer stockThreshold;  // 库存预警阈值（TDD 红阶段占位字段）
    private Integer type;            // 1-实物 2-虚拟
    private String images;           // 图片URL，逗号分隔
    private String spec;             // 规格参数
    private Integer status;          // 0-下架 1-上架
    private Date createdAt;
    private Date updatedAt;

    // ---- 店铺商品管理 TDD 红阶段占位字段（shop-product-list.md 功能上下文 1.1）----
    private BigDecimal marketPrice;  // 市场价（划线原价）
    private Integer sales;           // 商品销量（真实成交，MQ 累加）
    private Integer fakeSales;       // 注水销量（商家可配置）
    private Integer storeStock;      // 门店库存
    private Integer productType;     // 商品类型 1-普通商品 2-秒杀商品
    private String deliveryMethods;  // 配送方式（逗号分隔：CITY/PICKUP/EXPRESS）
    private Integer sort;            // 排序（数字越大前台越靠前）

    // ---- C 端前台销量（mall-category-product-list.md 功能上下文 5.1.1）：只读计算列，由 SQL 映射 ----
    private Integer displaySales;    // 前台销量 = sales + COALESCE(fake_sales, 0)（DAO 计算列，商家端查询不映射）
}
