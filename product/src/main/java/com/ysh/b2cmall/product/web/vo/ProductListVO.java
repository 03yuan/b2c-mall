package com.ysh.b2cmall.product.web.vo;

import lombok.Data;

import java.util.List;

/**
 * 商品列表响应 VO（shop-product-list.md 功能上下文 3.2）：
 * list + total；空结果约定返回空列表 + total=0（「暂无数据」语义，不伪造数值）。
 */
@Data
public class ProductListVO {

    /** 当前页数据行（空结果为空集合） */
    private List<ProductItemVO> list;

    /** 条件命中总数（空结果为 0） */
    private long total;
}
