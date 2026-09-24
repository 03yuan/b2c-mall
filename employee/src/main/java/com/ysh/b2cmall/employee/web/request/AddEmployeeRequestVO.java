package com.ysh.b2cmall.employee.web.request;

import lombok.Data;

@Data
public class AddEmployeeRequestVO {

    private Integer shopId;
    private String username;
    private String password;
}
