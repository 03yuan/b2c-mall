package com.ysh.order.web.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 买家端订单分页结果（shop-order-list.md 功能上下文 10.2）：
 * 当前页卡片 + 条件命中总数（total 供前端分页展示）。
 */
@Data
@AllArgsConstructor
public class BuyerOrderPageVO {

    /** 当前页卡片 */
    private List<BuyerOrderCardVO> rows;

    /** 条件命中总数 */
    private int total;
}
