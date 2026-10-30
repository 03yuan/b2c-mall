package com.ysh.b2cmall.product.web.vo;

import lombok.Data;

/**
 * 商品分类下拉项 VO（shop-product-list.md 功能上下文 3.3）：
 * 只暴露 id + name，供列表筛选与批量设置的「商品分类」下拉。
 */
@Data
public class CategoryOptionVO {

    private Integer id;

    private String name;
}
