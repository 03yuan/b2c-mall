package com.ysh.b2cmall.product.dao.po;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 动态条件分页查询结果（shop-product-list.md 功能上下文 3.2）：
 * 当前页行 + 条件命中总数（total 不受分页影响，供前端分页条展示）。
 */
@Data
@AllArgsConstructor
public class SkuPageResult {

    /** 当前页数据行 */
    private List<SkuPO> rows;

    /** 条件命中总数 */
    private int total;
}
