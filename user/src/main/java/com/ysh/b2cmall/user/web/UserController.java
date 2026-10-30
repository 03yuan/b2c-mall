package com.ysh.b2cmall.user.web;

import com.ysh.b2cmall.common.response.BaseResponseVO;
import com.ysh.b2cmall.user.service.UserService;
import com.ysh.b2cmall.user.web.request.LoginRequestVO;
import com.ysh.b2cmall.user.web.request.RegisterRequestVO;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public BaseResponseVO<Map<String, Object>> create(@RequestBody @Valid RegisterRequestVO req) {
        Long userId = userService.register(req);
        Map<String, Object> data = new HashMap<>();
        data.put("userId", userId);
        return new BaseResponseVO<>(data);
    }

    @PostMapping("/login")
    public BaseResponseVO<Map<String, Object>> login(@RequestBody @Valid LoginRequestVO req) {
        return new BaseResponseVO<>(userService.login(req));
    }
}