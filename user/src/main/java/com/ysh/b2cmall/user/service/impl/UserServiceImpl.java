package com.ysh.b2cmall.user.service.impl;

import cn.hutool.json.JSONUtil;
import com.ysh.b2cmall.user.dao.mapper.UserMapper;
import com.ysh.b2cmall.user.dao.po.UserPO;
import com.ysh.b2cmall.user.service.UserService;
import com.ysh.b2cmall.user.web.request.UserLoginRequestVO;
import com.ysh.b2cmall.user.web.request.UserRegisterRequestVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class UserServiceImpl implements UserService {

    private static final String TOKEN_KEY_PREFIX = "b2c:token:";
    private static final long TOKEN_TTL_HOURS = 24L;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public Map<String, Object> login(UserLoginRequestVO req) {
        UserPO user = userMapper.selectByUsername(req.getUsername());
        if (user == null || !req.getPassword().equals(user.getPassword())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new IllegalArgumentException("账号已禁用");
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("userId", user.getId());
        userInfo.put("username", user.getUsername());
        userInfo.put("nickname", user.getNickname());
        userInfo.put("avatarUrl", user.getAvatarUrl());

        redisTemplate.opsForValue().set(
                TOKEN_KEY_PREFIX + token,
                JSONUtil.toJsonStr(userInfo),
                TOKEN_TTL_HOURS,
                TimeUnit.HOURS
        );

        Map<String, Object> result = new HashMap<>(userInfo);
        result.put("token", token);
        return result;
    }

    @Override
    public Integer register(UserRegisterRequestVO req) {
        if (!req.getPassword().equals(req.getConfirmPassword())) {
            throw new IllegalArgumentException("两次输入的密码不一致");
        }
        UserPO exist = userMapper.selectByUsername(req.getUsername());
        if (exist != null) {
            throw new IllegalArgumentException("用户名已存在");
        }

        UserPO user = new UserPO();
        user.setUsername(req.getUsername());
        user.setPassword(req.getPassword());
        user.setStatus(1);
        userMapper.insert(user);
        return user.getId();
    }
}
