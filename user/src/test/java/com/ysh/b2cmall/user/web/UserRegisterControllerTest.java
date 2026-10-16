package com.ysh.b2cmall.user.web;

import com.ysh.b2cmall.user.service.UserService;
import com.ysh.b2cmall.user.web.request.UserRegisterRequestVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserRegisterControllerTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private UserController userController;

    @Test
    void register_成功_返回新用户id且不含token() {
        UserRegisterRequestVO req = new UserRegisterRequestVO();
        req.setUsername("newuser");
        req.setPassword("123456");
        req.setConfirmPassword("123456");
        when(userService.register(req)).thenReturn(10);

        Integer id = userController.register(req).getData();

        ArgumentCaptor<UserRegisterRequestVO> captor = ArgumentCaptor.forClass(UserRegisterRequestVO.class);
        verify(userService).register(captor.capture());
        assertEquals("newuser", captor.getValue().getUsername());
        assertEquals("123456", captor.getValue().getPassword());
        assertEquals(10, id);
    }
}
