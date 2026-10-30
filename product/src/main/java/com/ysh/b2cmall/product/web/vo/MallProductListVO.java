package com.ysh.b2cmall.product.web.vo;

import lombok.Data;

import java.util.List;

/**
 * C 端商品卡片列表响应 VO（mall-category-product-list.md 功能上下文 2.3）：
 * list + total；空结果约定返回空列表 + total=0（不伪造数值）。
 */
@Data
public class MallProductListVO {

    /** 当前页卡片数据（空结果为空集合） */
    private List<MallProductItemVO> list;

    /** 条件命中总数（空结果为 0） */
    private long total;
}
