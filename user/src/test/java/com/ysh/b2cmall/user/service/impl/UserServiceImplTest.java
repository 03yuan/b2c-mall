package com.ysh.b2cmall.user.service.impl;

import com.ysh.b2cmall.user.dao.mapper.UserMapper;
import com.ysh.b2cmall.user.dao.po.UserPO;
import com.ysh.b2cmall.user.web.request.UserLoginRequestVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private UserServiceImpl userService;

    private UserLoginRequestVO buildReq(String username, String password) {
        UserLoginRequestVO req = new UserLoginRequestVO();
        req.setUsername(username);
        req.setPassword(password);
        return req;
    }

    @Test
    void login_用户不存在_报用户名或密码错误() {
        when(userMapper.selectByUsername("nobody")).thenReturn(null);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> userService.login(buildReq("nobody", "123456")));

        assertEquals("用户名或密码错误", e.getMessage());
    }

    @Test
    void login_密码错误_报用户名或密码错误() {
        UserPO user = new UserPO();
        user.setId(1);
        user.setUsername("test");
        user.setPassword("123456");
        user.setStatus(1);
        when(userMapper.selectByUsername("test")).thenReturn(user);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> userService.login(buildReq("test", "666666")));

        assertEquals("用户名或密码错误", e.getMessage());
    }

    @Test
    void login_账号被禁用_报账号已禁用() {
        UserPO user = new UserPO();
        user.setId(1);
        user.setUsername("test");
        user.setPassword("123456");
        user.setStatus(0);
        when(userMapper.selectByUsername("test")).thenReturn(user);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> userService.login(buildReq("test", "123456")));

        assertEquals("账号已禁用", e.getMessage());
    }

    @Test
    void login_按用户名查询用户_查询参数正确() {
        when(userMapper.selectByUsername("test")).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> userService.login(buildReq("test", "123456")));

        verify(userMapper).selectByUsername("test");
    }

    @Test
    void login_失败_不写Redis() {
        UserPO user = new UserPO();
        user.setId(1);
        user.setUsername("test");
        user.setPassword("123456");
        user.setStatus(1);
        when(userMapper.selectByUsername("test")).thenReturn(user);

        assertThrows(IllegalArgumentException.class,
                () -> userService.login(buildReq("test", "666666")));

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void login_成功_写Redis并返回token和用户信息() {
        UserPO user = new UserPO();
        user.setId(1);
        user.setUsername("test");
        user.setPassword("123456");
        user.setNickname("测试用户");
        user.setAvatarUrl("/avatars/default.png");
        user.setStatus(1);
        when(userMapper.selectByUsername("test")).thenReturn(user);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        Map<String, Object> result = userService.login(buildReq("test", "123456"));

        assertEquals(1, result.get("userId"));
        assertEquals("test", result.get("username"));
        assertEquals("测试用户", result.get("nickname"));
        String token = (String) result.get("token");
        assertNotNull(token);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("b2c:token:" + token), valueCaptor.capture(), eq(24L), eq(TimeUnit.HOURS));
        assertTrue(valueCaptor.getValue().contains("\"userId\":1"));
    }
}
