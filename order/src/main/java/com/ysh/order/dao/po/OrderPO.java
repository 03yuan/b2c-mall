package com.ysh.order.dao.po;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OrderPO {
    private Integer id;
    private String orderNo;
    private Integer shopId;
    private Integer skuId;
    private String skuName;
    private Integer quantity;
    private BigDecimal totalPrice;
    private String status;
    private String payType;
    private String receiverName;
    private String receiverPhone;
    private String receiverAddress;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
