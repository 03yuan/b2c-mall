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
    private Integer type;            // 1-实物 2-虚拟
    private String images;           // 图片URL，逗号分隔
    private String spec;             // 规格参数
    private Integer status;          // 0-下架 1-上架
    private Date createdAt;
    private Date updatedAt;
}
