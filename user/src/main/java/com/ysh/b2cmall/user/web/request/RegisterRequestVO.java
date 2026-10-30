package com.ysh.b2cmall.user.web.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

@Data
public class RegisterRequestVO {

    @JsonDeserialize(using = StrictTextDeserializer.class)
    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_]{3,20}$", message = "用户名必须为3-20位字母、数字或下划线")
    private String username;

    @JsonDeserialize(using = StrictTextDeserializer.class)
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度必须为6-20位")
    private String password;

    @JsonDeserialize(using = StrictTextDeserializer.class)
    @Size(max = 50, message = "昵称不能超过50字符")
    private String nickname;
}