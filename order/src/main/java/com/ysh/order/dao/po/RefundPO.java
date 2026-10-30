package com.ysh.order.dao.po;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 售后单据（tb_refund），行 VO 的售后状态由该表派生 */
@Data
public class RefundPO {

    private Integer id;

    private Integer orderId;

    private Integer shopId;

    /** PROCESSING=退款申请中，DONE=退款完成 */
    private String status;

    private BigDecimal amount;

    private LocalDateTime createTime;
}
