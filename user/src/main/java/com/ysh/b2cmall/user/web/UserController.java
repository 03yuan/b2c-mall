package com.ysh.b2cmall.user.web;

import com.ysh.b2cmall.common.response.BaseResponseVO;
import com.ysh.b2cmall.user.service.UserService;
import com.ysh.b2cmall.user.web.request.UserLoginRequestVO;
import com.ysh.b2cmall.user.web.request.UserRegisterRequestVO;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.Map;

@RestController
@RequestMapping("/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/login")
    public BaseResponseVO<Map<String, Object>> login(@RequestBody @Valid UserLoginRequestVO req) {
        Map<String, Object> result = userService.login(req);
        return new BaseResponseVO<>(result);
    }

    @PostMapping("/register")
    public BaseResponseVO<Integer> register(@RequestBody @Valid UserRegisterRequestVO req) {
        Integer id = userService.register(req);
        return new BaseResponseVO<>(id);
    }
}
