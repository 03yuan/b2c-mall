package com.ysh.b2cmall.shop.service.event;

import lombok.Data;

@Data
public class ShopRegisterEvent {

    private Integer shopId;
    private String shopName;
    private String adminAccount;
    private String adminPwd;
}
