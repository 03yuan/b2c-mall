package com.ysh.order.web;

import com.ysh.order.bean.CurrentEmployeeBean;
import com.ysh.order.component.RequestHolderComponent;
import com.ysh.order.service.OrderService;
import com.ysh.order.web.advice.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 买家收藏/取消收藏 Controller 契约测试（TDD 红阶段）。
 * 对应 .tdd/task/mall-order-manage.md 功能上下文 6.2（Controller + Service 层 - 收藏/取消收藏接口）：
 * - 6.2.1 POST /order/buyer/fav/{orderId}：本人且未删 -> 200 + DB fav=1；他人/不存在/已删 -> 404
 * - 6.2.2 POST /order/buyer/unfav/{orderId}：同上契约，fav 置 0，幂等
 *
 * 买家身份强制：username 取自登录上下文；商家身份/无登录态 -> 401。
 * standalone MockMvc + mock OrderService；被测物为 BuyerOrderController。
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：fav/unfav 透传登录 username + orderId
 * 2. 类型与格式：200 空 body
 * 3. 重复创建：重复提交均调用
 * 4. 非法输入：他人/不存在 404，无登录态/商家 401
 */
class BuyerFavControllerTest {

    private static final String LOGIN_USERNAME = "buyer_a";

    private OrderService orderService;
    private RequestHolderComponent requestHolder;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        requestHolder = mock(RequestHolderComponent.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new BuyerOrderController(orderService, requestHolder))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private void buyerLogin() {
        CurrentEmployeeBean current = new CurrentEmployeeBean();
        current.setUsername(LOGIN_USERNAME);
        when(requestHolder.get()).thenReturn(current);
    }

    private void merchantLogin() {
        CurrentEmployeeBean current = new CurrentEmployeeBean();
        current.setEmployeeId(9);
        current.setShopId(2);
        current.setUsername("merchant_a");
        when(requestHolder.get()).thenReturn(current);
    }

    // ============ 1. 字段级校验：fav/unfav 透传登录 username + orderId ============

    @Test
    void 收藏_透传登录username() throws Exception {
        buyerLogin();

        int status = mockMvc.perform(post("/order/buyer/fav/1"))
                .andReturn().getResponse().getStatus();

        assertEquals(200, status, "收藏应返回 200");
        verify(orderService, times(1)).favByBuyer(1, LOGIN_USERNAME);
    }

    @Test
    void 取消收藏_透传登录username() throws Exception {
        buyerLogin();

        int status = mockMvc.perform(post("/order/buyer/unfav/1"))
                .andReturn().getResponse().getStatus();

        assertEquals(200, status, "取消收藏应返回 200");
        verify(orderService, times(1)).unfavByBuyer(1, LOGIN_USERNAME);
    }

    // ============ 2. 类型与格式：orderId 透传正确 ============

    @Test
    void 收藏_orderId透传正确() throws Exception {
        buyerLogin();

        mockMvc.perform(post("/order/buyer/fav/99"))
                .andReturn().getResponse().getStatus();

        verify(orderService, times(1)).favByBuyer(99, LOGIN_USERNAME);
    }

    // ============ 3. 重复创建：重复提交均调用 ============

    @Test
    void 收藏_重复提交_两次均调用() throws Exception {
        buyerLogin();

        int first = mockMvc.perform(post("/order/buyer/fav/1"))
                .andReturn().getResponse().getStatus();
        int second = mockMvc.perform(post("/order/buyer/fav/1"))
                .andReturn().getResponse().getStatus();

        assertEquals(first, second, "重复提交响应状态一致");
        verify(orderService, times(2)).favByBuyer(1, LOGIN_USERNAME);
    }

    // ============ 4. 非法输入：他人/不存在 404，无登录态/商家 401 ============

    @Test
    void 收藏_他人订单404() throws Exception {
        buyerLogin();
        doThrow(new IllegalArgumentException("订单不存在: 1"))
                .when(orderService).favByBuyer(eq(1), eq(LOGIN_USERNAME));

        int status = mockMvc.perform(post("/order/buyer/fav/1"))
                .andReturn().getResponse().getStatus();

        assertEquals(404, status, "收藏他人订单应返回 404");
    }

    @Test
    void 取消收藏_他人订单404() throws Exception {
        buyerLogin();
        doThrow(new IllegalArgumentException("订单不存在: 1"))
                .when(orderService).unfavByBuyer(eq(1), eq(LOGIN_USERNAME));

        int status = mockMvc.perform(post("/order/buyer/unfav/1"))
                .andReturn().getResponse().getStatus();

        assertEquals(404, status, "取消收藏他人订单应返回 404");
    }

    @Test
    void 收藏_无登录态401() throws Exception {
        when(requestHolder.get()).thenReturn(null);

        int status = mockMvc.perform(post("/order/buyer/fav/1"))
                .andReturn().getResponse().getStatus();

        assertEquals(401, status, "无登录态应返回 401");
        verifyNoInteractions(orderService);
    }

    @Test
    void 取消收藏_商家身份401() throws Exception {
        merchantLogin();

        int status = mockMvc.perform(post("/order/buyer/unfav/1"))
                .andReturn().getResponse().getStatus();

        assertEquals(401, status, "商家身份访问买家取消收藏接口应返回 401");
        verifyNoInteractions(orderService);
    }
}
