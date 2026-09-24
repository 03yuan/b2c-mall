package com.ysh.b2cmall.shop.dao.po;

import lombok.Data;
import java.util.Date;

@Data
public class ShopPO {

    private Integer id;
    private String shopName;
    private String adminAccount;
    private String adminPassword;
    private String logoUrl;
    private Integer status;
    private Date createdAt;
    private Date updatedAt;
}
