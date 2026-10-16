package com.ysh.b2cmall.user.web;

import com.ysh.b2cmall.user.service.UserService;
import com.ysh.b2cmall.user.web.request.UserLoginRequestVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private UserController userController;

    @Test
    void login_正常登录_调用service并返回token() {
        UserLoginRequestVO req = new UserLoginRequestVO();
        req.setUsername("test");
        req.setPassword("123456");

        Map<String, Object> serviceResult = new HashMap<>();
        serviceResult.put("userId", 1);
        serviceResult.put("token", "abc123");
        when(userService.login(req)).thenReturn(serviceResult);

        Map<String, Object> data = userController.login(req).getData();

        ArgumentCaptor<UserLoginRequestVO> captor = ArgumentCaptor.forClass(UserLoginRequestVO.class);
        verify(userService).login(captor.capture());
        assertEquals("test", captor.getValue().getUsername());
        assertEquals("123456", captor.getValue().getPassword());
        assertSame(serviceResult, data);
        assertEquals("abc123", data.get("token"));
    }
}
