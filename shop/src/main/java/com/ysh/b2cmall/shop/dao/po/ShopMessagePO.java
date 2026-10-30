package com.ysh.b2cmall.shop.dao.po;

import lombok.Data;

import java.util.Date;

/**
 * 店铺消息（订单事件消费落库，表：tb_shop_message）。
 */
@Data
public class ShopMessagePO {

    private Integer id;
    private Integer shopId;
    /** 事件类型：PAY_SUCCESS / REFUND_APPLY / CONFIRM_RECEIPT */
    private String type;
    /** 消息文本（三种固定模板拼出，逐字不得漂移） */
    private String content;
    /** 关联订单ID（前端点击跳订单详情的依据） */
    private Integer orderId;
    private Date createdAt;
}
