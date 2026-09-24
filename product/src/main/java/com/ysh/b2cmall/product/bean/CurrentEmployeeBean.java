package com.ysh.b2cmall.product.bean;

import lombok.Data;

/**
 * 当前登录员工上下文（ThreadLocal 持有）
 */
@Data
public class CurrentEmployeeBean {

    private Integer employeeId;
    private Integer shopId;
    private String username;
}
