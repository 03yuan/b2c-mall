package com.ysh.order.web.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款信息 VO（shop-order-list.md 功能上下文 9.3）：
 * 存在售后单据时返回 status/amount/createTime；无单据时返回 null（「暂无售后」语义，非 404/500）。
 */
@Data
public class RefundInfoVO {

    /** 售后状态（PROCESSING=退款申请中 / DONE=退款完成） */
    private String status;

    /** 退款金额 */
    private BigDecimal amount;

    /** 退款单创建时间 */
    private LocalDateTime createTime;
}
