package com.ysh.b2cmall.shop.web.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Date;

/**
 * 消息通知条目（Dashboard 消息滚动列表）。
 */
@Data
public class MessageVO {

    /** 消息文本（固定模板拼出） */
    private String content;

    /** 关联订单ID（前端点击跳订单详情的依据） */
    private Integer orderId;

    /** 创建时间 */
    @JsonProperty("created_at")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createdAt;

    /** 事件类型：PAY_SUCCESS / REFUND_APPLY / CONFIRM_RECEIPT */
    private String type;
}
