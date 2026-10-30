package com.ysh.b2cmall.user.web.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class LoginRequestVO {

    @JsonDeserialize(using = StrictTextDeserializer.class)
    @NotBlank(message = "用户名不能为空")
    private String username;

    @JsonDeserialize(using = StrictTextDeserializer.class)
    @NotBlank(message = "密码不能为空")
    private String password;
}