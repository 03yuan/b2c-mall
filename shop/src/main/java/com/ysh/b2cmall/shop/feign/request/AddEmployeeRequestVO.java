package com.ysh.b2cmall.shop.feign.request;

import lombok.Data;

@Data
public class AddEmployeeRequestVO {

    private Integer shopId;
    private String username;
    private String password;
}
