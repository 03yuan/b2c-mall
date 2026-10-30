package com.ysh.order.dao.po;

import lombok.Data;

/**
 * 商家端订单列表筛选条件（shop-order-list.md 功能上下文 5.1）。
 * shopId 由登录上下文强制注入，请求参数中的 shopId 一律忽略。
 */
@Data
public class OrderQueryPO {

    /** 登录店铺 ID（强制条件） */
    private Integer shopId;

    /** 订单编号：精确匹配 */
    private String orderNo;

    /** 订单类型（NORMAL/SECKILL）：等值 */
    private String orderType;

    /** 订单类别（NORMAL/CARD）：等值 */
    private String orderCategory;

    /** 订单状态（快捷标签即该字段的预设值）：等值 */
    private String status;

    /** 收件人姓名：模糊匹配 */
    private String receiverName;

    /** 联系电话：模糊匹配 */
    private String receiverPhone;

    /** 支付方式（BALANCE/WECHAT/ALIPAY）：等值 */
    private String payType;

    /** 售后状态（NONE/PROCESSING/DONE）：派生自 tb_refund */
    private String refundStatus;

    /** 物流类型（EXPRESS/CITY）：等值 */
    private String logisticsType;

    /** 门店名称：等值 */
    private String storeName;

    /** 下单开始日期 yyyy-MM-dd（含当日 00:00:00） */
    private String startDate;

    /** 下单结束日期 yyyy-MM-dd（含当日 23:59:59） */
    private String endDate;

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
