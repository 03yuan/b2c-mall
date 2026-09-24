package com.ysh.order.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OrderLogPO {
    private Integer id;
    private Integer orderId;
    private String fromStatus;
    private String toStatus;
    private String event;
    private Integer operatorId;
    private String remark;
    private LocalDateTime createTime;
}
