package com.ysh.b2cmall.shop.web.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class ShopRegisterRequestVO {

    @NotBlank(message = "店铺名称不能为空")
    @Size(max = 100, message = "店铺名称不能超过100个字符")
    private String shopName;

    @NotBlank(message = "管理员账号不能为空")
    @Size(max = 100, message = "账号不能超过100个字符")
    private String adminAccount;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 100, message = "密码长度需要在6-100之间")
    private String adminPassword;

    private String logoUrl;
}
