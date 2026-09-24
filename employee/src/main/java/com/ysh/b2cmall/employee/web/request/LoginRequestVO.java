package com.ysh.b2cmall.employee.web.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 登录请求 VO。
 * 员工 username 只在 shop_id 内唯一（不同店铺可重名），所以登录必须带 shopId。
 */
@Data
public class LoginRequestVO {

    @NotNull(message = "店铺ID不能为空")
    private Integer shopId;

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}
