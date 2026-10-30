package com.ysh.b2cmall.product.dao.po;

import lombok.Data;

import java.util.List;

/**
 * 商品列表动态查询参数（shop-product-list.md 功能上下文 3.1）。
 * shopId 恒由登录上下文注入（2.2，请求参数 shopId 忽略）；
 * name 为模糊关键词（DAO 侧加 % 匹配）；productType/type/status/categoryId 等值；
 * deliveryMethods 多选（命中任一所选即返回）；
 * 分页 page≥1、size 限 1~100（校验待绿阶段补充）。
 */
@Data
public class SkuQuery {

    /** 所属店铺（恒取登录上下文） */
    private Integer shopId;

    /** 商品名称模糊关键词 */
    private String name;

    /** 商品类型 1-普通商品 2-秒杀商品 */
    private Integer productType;

    /** 商品类别 1-实物 2-虚拟 3-卡券 */
    private Integer type;

    /** 状态 0-下架 1-上架 */
    private Integer status;

    /** 类目ID */
    private Integer categoryId;

    /** 配送方式多选（命中任一所选即返回） */
    private List<String> deliveryMethods;

    /** 页码（≥1） */
    private Integer page = 1;

    /** 每页条数（1~100） */
    private Integer size = 10;

    /** 分页边界（功能上下文 3.1.3）：page≥1，非法值直接拒绝而非落进 SQL 异常 */
    public void setPage(Integer page) {
        if (page == null || page < 1) {
            throw new IllegalArgumentException("页码必须大于等于 1");
        }
        this.page = page;
    }

    /** 分页边界（功能上下文 3.1.3）：size 限 1~100，非法值直接拒绝而非落进 SQL 异常 */
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
