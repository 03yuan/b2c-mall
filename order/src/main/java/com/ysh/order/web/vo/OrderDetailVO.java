package com.ysh.order.web.vo;

import com.ysh.order.dao.po.OrderLogPO;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商家端订单详情 VO（shop-order-list.md 功能上下文 9.1）：
 * 行 VO 全字段 + 收货地址、物流单号/类型、支付时间 + 状态流转日志（tb_order_log 按时间升序）。
 */
@Data
public class OrderDetailVO extends OrderRowVO {

    /** 收货地址 */
    private String receiverAddress;

    /** 物流单号 */
    private String logisticsNo;

    /** 物流类型（EXPRESS/CITY） */
    private String logisticsType;

    /** 支付时间 */
    private LocalDateTime payTime;

    /** 状态流转日志（按时间升序） */
    private List<OrderLogPO> logs;
}
