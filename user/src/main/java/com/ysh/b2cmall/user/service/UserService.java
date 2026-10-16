package com.ysh.b2cmall.user.service;

import com.ysh.b2cmall.user.web.request.UserLoginRequestVO;
import com.ysh.b2cmall.user.web.request.UserRegisterRequestVO;

import java.util.Map;

public interface UserService {

    Map<String, Object> login(UserLoginRequestVO req);

    Integer register(UserRegisterRequestVO req);
}
