package com.ysh.b2cmall.product.dao.po;

/**
 * C 端商城商品列表排序枚举（mall-category-product-list.md 功能上下文 3.1）。
 * ORDER BY 只能在枚举白名单内拼接，杜绝 SQL 注入：
 * 枚举是编译期白名单，SQL 片段硬编码于枚举常量，Mapper XML 用 ${sortBy.sql} 只能取到预定义片段，
 * 任意字符串无法注入（枚举外值在 setter/Controller 层即被拒绝）。
 *
 * 四种排序语义：
 * - COMPREHENSIVE（默认）：sort DESC, id DESC（数字越大越靠前，同 sort 新商品在前）
 * - SALES：按前台销量 (sales + COALESCE(fake_sales,0)) DESC, id DESC（真实 + 注水口径）
 * - PRICE_ASC：price ASC, id DESC（BigDecimal 数值升序，非字符串排序）
 * - PRICE_DESC：price DESC, id DESC（数值降序）
 */
public enum MallSortType {

    COMPREHENSIVE("sort DESC, id DESC"),
    SALES("(sales + COALESCE(fake_sales, 0)) DESC, id DESC"),
    PRICE_ASC("price ASC, id DESC"),
    PRICE_DESC("price DESC, id DESC");

    /** 预定义的 ORDER BY 片段（白名单，禁止任意字符串拼接） */
    private final String sql;

    MallSortType(String sql) {
        this.sql = sql;
    }

    /** 供 Mapper XML ${sortBy.sql} 引用的白名单 SQL 片段 */
    public String getSql() {
        return sql;
    }
}
