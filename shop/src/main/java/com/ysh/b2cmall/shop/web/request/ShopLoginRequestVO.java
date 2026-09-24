package com.ysh.b2cmall.shop.web.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class ShopLoginRequestVO {

    @NotBlank(message = "店铺名称不能为空")
    private String shopName;

    @NotBlank(message = "管理员账号不能为空")
    private String adminAccount;

    @NotBlank(message = "密码不能为空")
    private String adminPassword;
}
