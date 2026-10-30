package com.ysh.b2cmall.product.dao.po;

import lombok.Data;

/**
 * C 端商城商品列表查询参数（mall-category-product-list.md 功能上下文 2.2/3）。
 * 公开免登录，不区分店铺（无 shopId，跨店聚合）；
 * 恒强制 status=1（只展示上架商品，不接收 status 参数）；
 * categoryName 可选（按一级分类名跨店过滤，null/空 = 全部）；
 * 分页 page≥1、size 限 1~100（非法值直接拒绝，沿用 SkuQuery setter 校验模式）。
 */
@Data
public class MallSkuQuery {

    /** 一级分类名（可选，跨店按名过滤；null 或空串 = 全部商品） */
    private String categoryName;

    /** 排序方式（枚举白名单，缺省 COMPREHENSIVE；枚举外值无法传入，防注入） */
    private MallSortType sortBy = MallSortType.COMPREHENSIVE;

    /** 页码（≥1） */
    private Integer page = 1;

    /** 每页条数（1~100） */
    private Integer size = 10;

    /** 分页边界：page≥1，非法值直接拒绝而非落进 SQL 异常 */
    public void setPage(Integer page) {
        if (page == null || page < 1) {
            throw new IllegalArgumentException("页码必须大于等于 1");
        }
        this.page = page;
    }

    /** 分页边界：size 限 1~100，非法值直接拒绝而非落进 SQL 异常 */
    public void setSize(Integer size) {
        if (size == null || size < 1 || size > 100) {
            throw new IllegalArgumentException("每页条数必须在 1~100 之间");
        }
        this.size = size;
    }

    /** SQL 分页偏移量：(page-1)*size */
    public int getOffset() {
        return (page - 1) * size;
    }
}
