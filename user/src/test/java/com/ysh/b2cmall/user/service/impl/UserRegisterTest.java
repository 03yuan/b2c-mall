package com.ysh.b2cmall.user.service.impl;

import com.ysh.b2cmall.user.dao.mapper.UserMapper;
import com.ysh.b2cmall.user.dao.po.UserPO;
import com.ysh.b2cmall.user.web.request.UserRegisterRequestVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserRegisterTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private UserServiceImpl userService;

    private UserRegisterRequestVO buildReq(String username, String password, String confirmPassword) {
        UserRegisterRequestVO req = new UserRegisterRequestVO();
        req.setUsername(username);
        req.setPassword(password);
        req.setConfirmPassword(confirmPassword);
        return req;
    }

    @Test
    void register_两次密码不一致_报错() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> userService.register(buildReq("test", "123456", "666666")));

        assertEquals("两次输入的密码不一致", e.getMessage());
    }

    @Test
    void register_用户名已存在_报错且不插入() {
        UserPO exist = new UserPO();
        exist.setId(1);
        exist.setUsername("test");
        when(userMapper.selectByUsername("test")).thenReturn(exist);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> userService.register(buildReq("test", "123456", "123456")));

        assertEquals("用户名已存在", e.getMessage());
        verify(userMapper, never()).insert(any());
    }

    @Test
    void register_成功_明文入库status为1并返回id() {
        when(userMapper.selectByUsername("newuser")).thenReturn(null);
        when(userMapper.insert(any(UserPO.class))).thenAnswer(inv -> {
            UserPO po = inv.getArgument(0);
            po.setId(10);
            return 1;
        });

        Integer id = userService.register(buildReq("newuser", "123456", "123456"));

        assertEquals(10, id);
        ArgumentCaptor<UserPO> captor = ArgumentCaptor.forClass(UserPO.class);
        verify(userMapper).insert(captor.capture());
        UserPO saved = captor.getValue();
        assertEquals("newuser", saved.getUsername());
        assertEquals("123456", saved.getPassword());
        assertEquals(1, saved.getStatus());
    }
}
