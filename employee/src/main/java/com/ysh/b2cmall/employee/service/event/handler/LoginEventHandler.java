package com.ysh.b2cmall.employee.service.event.handler;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.ysh.b2cmall.employee.dao.mapper.EmployeeMapper;
import com.ysh.b2cmall.employee.service.event.LoginEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 登录事件观察者（同一个类承载 2 个观察者，避免新增多个类）：
 * 1. 审计日志观察者：写 tb_login_logs（成功失败都写）
 * 2. 用户状态观察者：更新 tb_employee.last_login_time + login_count（仅成功时执行）
 */
@Slf4j
@Component
public class LoginEventHandler implements InitializingBean {

    @Autowired
    private EventBus eventBus;

    @Autowired
    private EmployeeMapper employeeMapper;

    /**
     * 审计日志观察者：记录 IP、设备、时间、地点、UA、状态。
     * 成功失败都写。
     */
    @Subscribe
    public void handleAuditLog(LoginEvent event) {
        log.info("[审计日志观察者] 登录事件：{}", event);
        try {
            employeeMapper.saveLoginLog(
                    event.getEmployeeId(),
                    event.getIp(),
                    event.getDevice(),
                    event.getLocation(),
                    event.getUserAgent(),
                    event.getStatus()
            );
        } catch (Exception e) {
            log.error("写入登录审计日志失败", e);
        }
    }

    /**
     * 用户状态观察者：更新 last_login_time + login_count。
     * 仅成功时执行。
     */
    @Subscribe
    public void handleUserState(LoginEvent event) {
        if (!Integer.valueOf(1).equals(event.getStatus())) {
            return;
        }
        if (event.getEmployeeId() == null) {
            return;
        }
        log.info("[用户状态观察者] 更新登录信息：employeeId={}", event.getEmployeeId());
        try {
            employeeMapper.updateLoginInfo(event.getEmployeeId());
        } catch (Exception e) {
            log.error("更新用户登录信息失败", e);
        }
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        eventBus.register(this);
    }
}
