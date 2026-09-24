package com.ysh.b2cmall.product.dao.po;

import lombok.Data;

import java.util.Date;

/**
 * 商品操作日志实体
 */
@Data
public class SkuLogPO {

    private Integer id;
    private Integer skuId;       // 商品ID
    private Integer operatorId;  // 操作人ID（员工ID）
    private String action;       // 操作类型：CREATE/UPDATE/PUT_ON_SALE/PUT_OFF_SALE
    private String content;      // 操作内容描述
    private Date createdAt;
}
