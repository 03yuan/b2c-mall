package com.ysh.b2cmall.user.service.impl;

import com.ysh.b2cmall.user.dao.mapper.UserMapper;
import com.ysh.b2cmall.user.dao.po.UserPO;
import com.ysh.b2cmall.user.web.request.UserLoginRequestVO;
import com.ysh.b2cmall.user.web.request.UserRegisterRequestVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegisterLoginContractTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private org.springframework.data.redis.core.ValueOperations<String, String> valueOps;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void 注册的明文密码_相同密码可直接登录() {
        UserRegisterRequestVO registerReq = new UserRegisterRequestVO();
        registerReq.setUsername("newuser");
        registerReq.setPassword("123456");
        registerReq.setConfirmPassword("123456");
        when(userMapper.selectByUsername("newuser")).thenReturn(null);
        when(userMapper.insert(any(UserPO.class))).thenAnswer(inv -> {
            UserPO po = inv.getArgument(0);
            po.setId(10);
            return 1;
        });

        userService.register(registerReq);

        ArgumentCaptor<UserPO> captor = ArgumentCaptor.forClass(UserPO.class);
        verify(userMapper).insert(captor.capture());
        UserPO saved = captor.getValue();

        UserLoginRequestVO loginReq = new UserLoginRequestVO();
        loginReq.setUsername("newuser");
        loginReq.setPassword("123456");
        when(userMapper.selectByUsername("newuser")).thenReturn(saved);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        Map<String, Object> result = userService.login(loginReq);
        assertNotNull(result.get("token"));
        assertEquals(10, result.get("userId"));
    }

    @Test
    void 注册的账号_错误密码登录被拒绝() {
        UserRegisterRequestVO registerReq = new UserRegisterRequestVO();
        registerReq.setUsername("newuser");
        registerReq.setPassword("123456");
        registerReq.setConfirmPassword("123456");
        when(userMapper.selectByUsername("newuser")).thenReturn(null);
        when(userMapper.insert(any(UserPO.class))).thenAnswer(inv -> {
            UserPO po = inv.getArgument(0);
            po.setId(10);
            return 1;
        });

        userService.register(registerReq);

        ArgumentCaptor<UserPO> captor = ArgumentCaptor.forClass(UserPO.class);
        verify(userMapper).insert(captor.capture());
        UserPO saved = captor.getValue();

        UserLoginRequestVO loginReq = new UserLoginRequestVO();
        loginReq.setUsername("newuser");
        loginReq.setPassword("666666");
        when(userMapper.selectByUsername("newuser")).thenReturn(saved);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> userService.login(loginReq));

        assertEquals("用户名或密码错误", e.getMessage());
    }
}
