package com.ysh.b2cmall.user.service;

import com.ysh.b2cmall.user.web.request.LoginRequestVO;
import com.ysh.b2cmall.user.web.request.RegisterRequestVO;

import java.util.Map;

public interface UserService {

    /**
     * 注册新用户
     *
     * @return 新用户ID
     */
    Long register(RegisterRequestVO req);

    /**
     * 登录：校验用户名密码与账号状态，成功返回 token 与用户信息
     */
    Map<String, Object> login(LoginRequestVO req);
}