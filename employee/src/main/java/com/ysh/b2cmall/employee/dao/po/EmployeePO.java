package com.ysh.b2cmall.employee.dao.po;

import lombok.Data;
import java.util.Date;

@Data
public class EmployeePO {

    private Integer id;
    private Integer shopId;
    private String username;
    private String password;
    private String avatarUrl;
    private Date lastLoginTime;
    private Integer loginCount;
    private Integer status;
    private Date createdAt;
    private Date updatedAt;
}
