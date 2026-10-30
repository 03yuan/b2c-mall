package com.ysh.b2cmall.user.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ysh.b2cmall.user.exception.BusinessException;
import com.ysh.b2cmall.user.exception.ErrorCode;
import com.ysh.b2cmall.user.service.UserService;
import com.ysh.b2cmall.user.web.request.LoginRequestVO;
import com.ysh.b2cmall.user.web.request.RegisterRequestVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class UserServiceImpl implements UserService {

    private static final String DEFAULT_AVATAR_URL = "/avatars/default.png";
    private static final String TOKEN_KEY_PREFIX = "b2c:token:";
    private static final long TOKEN_TTL_HOURS = 24L;

    private final Map<String, UserRecord> users = new ConcurrentHashMap<>();
    private final AtomicInteger userIdSequence = new AtomicInteger(0);

    private StringRedisTemplate redisTemplate;
    private ObjectMapper objectMapper;

    public UserServiceImpl(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;

        UserRecord disabled = new UserRecord();
        disabled.id = (long) userIdSequence.incrementAndGet();
        disabled.username = "disabled_user";
        disabled.password = "123456";
        disabled.nickname = "disabled_user";
        disabled.avatarUrl = DEFAULT_AVATAR_URL;
        disabled.status = 0;
        users.put(disabled.username, disabled);
    }

    @Override
    public Long register(RegisterRequestVO req) {
        if (users.containsKey(req.getUsername())) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS.getMessage());
        }
        UserRecord record = new UserRecord();
        record.id = (long) userIdSequence.incrementAndGet();
        record.username = req.getUsername();
        record.password = req.getPassword();
        record.nickname = req.getNickname() == null ? req.getUsername() : req.getNickname();
        record.avatarUrl = DEFAULT_AVATAR_URL;
        record.status = 1;
        users.put(record.username, record);
        return record.id;
    }

    @Override
    public Map<String, Object> login(LoginRequestVO req) {
        UserRecord record = users.get(req.getUsername());
        if (record == null || !record.password.equals(req.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS.getMessage());
        }
        if (record.status != 1) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED.getMessage());
        }
        String token = UUID.randomUUID().toString().replace("-", "");

        // 买家登录态写 Redis（与 employee/shop 共用 b2c:token: 前缀；载荷含 userId 供 order-service 区分买家身份）
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("userId", record.id);
        userInfo.put("username", record.username);
        try {
            redisTemplate.opsForValue().set(
                    TOKEN_KEY_PREFIX + token,
                    objectMapper.writeValueAsString(userInfo),
                    TOKEN_TTL_HOURS,
                    TimeUnit.HOURS
            );
        } catch (Exception e) {
            throw new RuntimeException("登录态写入失败", e);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("userId", record.id);
        data.put("username", record.username);
        data.put("nickname", record.nickname);
        data.put("avatarUrl", record.avatarUrl);
        return data;
    }

    private static class UserRecord {
        private Long id;
        private String username;
        private String password;
        private String nickname;
        private String avatarUrl;
        private Integer status;
    }
}