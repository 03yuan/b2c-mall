package com.ysh.b2cmall.employee.service;

import com.ysh.b2cmall.employee.web.request.AddEmployeeRequestVO;
import com.ysh.b2cmall.employee.web.request.LoginRequestVO;

import java.util.Map;

public interface EmployeeService {

    Integer addEmployee(AddEmployeeRequestVO vo);

    /**
     * 员工登录：校验账号密码，成功后生成 token 写入 Redis 并发布 LoginEvent。
     * 失败也会发布 LoginEvent（status=0），供审计观察者记录失败尝试。
     *
     * @return 包含 token / employeeId / shopId / username / avatarUrl 的结果 Map
     */
    Map<String, Object> login(LoginRequestVO vo, String ip, String userAgent);
}
