package com.ysh.order.dao.po;

import com.ysh.order.web.vo.OrderRowVO;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 商家端订单分页查询结果（shop-order-list.md 功能上下文 5.2 / 6.1）：
 * 当前页行 VO + 条件命中总数（total 不受分页影响，供前端分页条展示）。
 */
@Data
@AllArgsConstructor
public class OrderPageResult {

    /** 当前页数据行 */
    private List<OrderRowVO> rows;

    /** 条件命中总数 */
    private int total;
}
