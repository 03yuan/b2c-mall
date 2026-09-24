package com.ysh.b2cmall.employee.service.event;

import lombok.Data;

import java.util.Date;

/**
 * 登录事件，由 EmployeeServiceImpl.login 发布，
 * 由 LoginEventHandler 中的 @Subscribe 方法消费（审计日志、用户状态）。
 */
@Data
public class LoginEvent {

    private Integer employeeId;   // 登录用户 ID（失败且用户不存在时为 null）
    private Integer shopId;
    private String username;
    private String ip;
    private String device;
    private String location;
    private String userAgent;
    private Date loginTime;
    private Integer status;      // 1=成功 0=失败
}
