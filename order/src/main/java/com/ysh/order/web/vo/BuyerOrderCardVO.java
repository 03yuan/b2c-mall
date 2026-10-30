package com.ysh.order.web.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 买家端订单卡片 VO（shop-order-list.md 功能上下文 10.2.1）。
 * status 保留原始枚举值 + 中文文案；待发货（PAID）卡片附带 payType 展示，其他状态不含。
 */
@Data
public class BuyerOrderCardVO {

    private Integer orderId;

    private String orderNo;

    /** 店铺名称（快照） */
    private String shopName;

    private LocalDateTime createTime;

    /** 原始枚举值（PENDING/PAID/SHIPPED/COMPLETED/FAILED/GROUP_PENDING） */
    private String status;

    /** 状态中文文案 */
    private String statusText;

    /** 支付方式（仅待发货卡片附带展示） */
    private String payType;

    /** 商品行数组：当前恒 1 条，多商品扩展时前端无需重写 */
    private List<BuyerOrderItemVO> items;

    /** 实付金额（当前模型 = 单行 total_price） */
    private BigDecimal totalPrice;

    /** 立即购买跳转用 skuId */
    private Integer skuId;

    /** 店铺电话（电话图标数据源，null 输出空串） */
    private String shopPhone;

    /** 收藏标记（0-未收藏 1-已收藏） */
    private Integer buyerFav;
}
