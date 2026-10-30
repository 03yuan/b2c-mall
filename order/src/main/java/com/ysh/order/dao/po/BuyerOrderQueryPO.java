package com.ysh.order.dao.po;

import lombok.Data;

/**
 * 买家端订单列表筛选条件（shop-order-list.md 功能上下文 10.1）。
 * buyerUsername 由登录上下文强制注入，请求参数中的 buyerUsername 一律忽略；
 * buyer_deleted = 0 恒置（已删除订单任何筛选下不出现）。
 */
@Data
public class BuyerOrderQueryPO {

    /** 登录买家账号（强制条件，恒写入 WHERE） */
    private String buyerUsername;

    /** 订单状态（状态 Tab 即该字段的预设值）：等值，可选 */
    private String status;

    /** 订单编号：精确匹配，可选 */
    private String orderNo;

    /** 页码，从 1 开始 */
    private Integer page = 1;

    /** 每页条数，1~100 */
    private Integer size = 10;

    /**
     * 分页参数校验：page>=1、size 1~100，非法抛 IllegalArgumentException（全局异常处理转 400）。
     */
    public void validatePaging() {
        if (page == null || page < 1) {
            throw new IllegalArgumentException("页码必须大于等于 1");
        }
        if (size == null || size < 1 || size > 100) {
            throw new IllegalArgumentException("每页条数必须在 1~100 之间");
        }
    }

    /**
     * SQL OFFSET 计算，page>=1、size 1~100，非法抛 IllegalArgumentException。
     */
    public int resolveOffset() {
        validatePaging();
        return (page - 1) * size;
    }
}
