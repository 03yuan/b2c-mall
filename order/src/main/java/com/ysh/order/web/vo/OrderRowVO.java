package com.ysh.order.web.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商家端订单列表行 VO（shop-order-list.md 功能上下文 6.1 / 6.2.1）。
 * status / refundStatus 保留原始枚举值，前端据此控制操作按钮显隐（文案改动不影响按钮逻辑）。
 */
@Data
public class OrderRowVO {

    private Integer orderId;

    private String orderNo;

    private LocalDateTime createTime;

    /** 门店名称（多门店订单非空） */
    private String storeName;

    /** 商品行数组：当前恒 1 条，多商品扩展时前端无需重写 */
    private List<OrderItemVO> items;

    /** 实付金额 */
    private BigDecimal totalPrice;

    /** 共 N 件 = Σ items.quantity */
    private Integer totalQuantity;

    private String payType;

    private String buyerUsername;

    private String receiverName;

    /** 脱敏手机号（列表展示口径；导出为明文） */
    private String receiverPhoneMasked;

    /** 原始枚举值（PENDING/PAID/SHIPPED/COMPLETED/FAILED/GROUP_PENDING） */
    private String status;

    /** 原始枚举值（NONE/PROCESSING/DONE） */
    private String refundStatus;

    /** 派生标签（待发货/待收货/退款申请中/电子卡券订单/同城配送/赠品） */
    private List<String> tags;
}
