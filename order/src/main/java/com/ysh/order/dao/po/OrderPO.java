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
    /** 支付时间（SQLite 存字符串，NULL 安全；详情组装时解析为 LocalDateTime） */
    private String payTime;
    private String orderType;
    private String orderCategory;
    private String logisticsType;
    private String logisticsNo;
    private String storeName;
    private String shopName;
    /** 店铺电话（下单快照冗余，电话图标数据源；可空，null 输出空串） */
    private String shopPhone;
    private String skuImage;
    private BigDecimal unitPrice;
    private Integer isGift;
    private Integer buyerDeleted;
    private Integer buyerFav;
    private String buyerUsername;
    private String receiverName;
    private String receiverPhone;
    private String receiverAddress;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
