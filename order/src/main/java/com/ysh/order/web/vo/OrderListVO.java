package com.ysh.order.web.vo;

import lombok.Data;

import java.util.List;

/**
 * 商家端订单列表响应体（shop-order-list.md 功能上下文 5.2）：
 * list 为空时前端渲染「暂无数据」，total 恒为筛选命中总数。
 */
@Data
public class OrderListVO {

    /** 当前页订单行 */
    private List<OrderRowVO> list;

    /** 筛选命中总数 */
    private int total;
}
