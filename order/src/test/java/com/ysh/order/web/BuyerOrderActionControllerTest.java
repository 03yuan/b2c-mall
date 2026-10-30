package com.ysh.order.web;

import com.ysh.order.bean.CurrentEmployeeBean;
import com.ysh.order.component.RequestHolderComponent;
import com.ysh.order.service.OrderService;
import com.ysh.order.web.advice.GlobalExceptionHandler;
import com.ysh.order.web.vo.OrderDetailVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 买家端单订单操作（详情/删除/取消）Controller 契约测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 11.1：
 * - 11.1.1 GET /order/buyer/{orderId}：透传登录 username，他人/不存在 -> 404
 * - 11.1.2 POST /order/buyer/delete/{orderId}：逻辑删除，幂等
 * - 11.1.3 POST /order/buyer/cancel/{orderId}：PENDING 取消，PAID -> 400
 *
 * 买家身份强制：username 取自登录上下文；商家身份/无登录态 -> 401。
 * standalone MockMvc + mock OrderService；被测物为 BuyerOrderController。
 */
class BuyerOrderActionControllerTest {

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

    // ============ 1. 字段级校验：详情透传登录 username ============

    @Test
    void 详情_透传登录username() throws Exception {
        buyerLogin();
        when(orderService.getBuyerOrderDetail(eq(1), eq(LOGIN_USERNAME)))
                .thenReturn(new OrderDetailVO());

        int status = mockMvc.perform(get("/order/buyer/1"))
                .andReturn().getResponse().getStatus();

        assertEquals(200, status, "本人订单详情应返回 200");
        verify(orderService, times(1)).getBuyerOrderDetail(1, LOGIN_USERNAME);
    }

    // ============ 2. 类型与格式：删除/取消透传 username ============

    @Test
    void 删除_透传登录username() throws Exception {
        buyerLogin();

        int status = mockMvc.perform(post("/order/buyer/delete/1"))
                .andReturn().getResponse().getStatus();

        assertEquals(200, status, "逻辑删除应返回 200");
        verify(orderService, times(1)).deleteByBuyer(1, LOGIN_USERNAME);
    }

    @Test
    void 取消_透传登录username() throws Exception {
        buyerLogin();

        int status = mockMvc.perform(post("/order/buyer/cancel/1"))
                .andReturn().getResponse().getStatus();

        assertEquals(200, status, "取消订单应返回 200");
        verify(orderService, times(1)).cancelByBuyer(1, LOGIN_USERNAME);
    }

    // ============ 3. 重复创建：重复提交 ============

    @Test
    void 删除_重复提交_两次均调用() throws Exception {
        buyerLogin();

        int first = mockMvc.perform(post("/order/buyer/delete/1"))
                .andReturn().getResponse().getStatus();
        int second = mockMvc.perform(post("/order/buyer/delete/1"))
                .andReturn().getResponse().getStatus();

        assertEquals(first, second, "重复提交响应状态一致");
        verify(orderService, times(2)).deleteByBuyer(1, LOGIN_USERNAME);
    }

    // ============ 4. 非法输入：他人/不存在 404，PAID 400，无登录态/商家 401 ============

    @Test
    void 详情_他人订单404() throws Exception {
        buyerLogin();
        doThrow(new IllegalArgumentException("订单不存在: 1"))
                .when(orderService).getBuyerOrderDetail(eq(1), eq(LOGIN_USERNAME));

        int status = mockMvc.perform(get("/order/buyer/1"))
                .andReturn().getResponse().getStatus();

        assertEquals(404, status, "他人/不存在订单详情应返回 404");
    }

    @Test
    void 删除_他人订单404() throws Exception {
        buyerLogin();
        doThrow(new IllegalArgumentException("订单不存在: 1"))
                .when(orderService).deleteByBuyer(eq(1), eq(LOGIN_USERNAME));

        int status = mockMvc.perform(post("/order/buyer/delete/1"))
                .andReturn().getResponse().getStatus();

        assertEquals(404, status, "删除他人订单应返回 404");
    }

    @Test
    void 取消_他人订单404() throws Exception {
        buyerLogin();
        doThrow(new IllegalArgumentException("订单不存在: 1"))
                .when(orderService).cancelByBuyer(eq(1), eq(LOGIN_USERNAME));

        int status = mockMvc.perform(post("/order/buyer/cancel/1"))
                .andReturn().getResponse().getStatus();

        assertEquals(404, status, "取消他人订单应返回 404");
    }

    @Test
    void 取消_PAID订单400() throws Exception {
        buyerLogin();
        doThrow(new IllegalStateException("已付款订单不能取消"))
                .when(orderService).cancelByBuyer(eq(1), eq(LOGIN_USERNAME));

        int status = mockMvc.perform(post("/order/buyer/cancel/1"))
                .andReturn().getResponse().getStatus();

        assertEquals(400, status, "已付款订单取消应返回 400（需走退款）");
    }

    @Test
    void 详情_无登录态401() throws Exception {
        when(requestHolder.get()).thenReturn(null);

        int status = mockMvc.perform(get("/order/buyer/1"))
                .andReturn().getResponse().getStatus();

        assertEquals(401, status, "无登录态应返回 401");
        verifyNoInteractions(orderService);
    }

    @Test
    void 删除_商家身份401() throws Exception {
        merchantLogin();

        int status = mockMvc.perform(post("/order/buyer/delete/1"))
                .andReturn().getResponse().getStatus();

        assertEquals(401, status, "商家身份访问买家接口应返回 401");
        verifyNoInteractions(orderService);
    }

    @Test
    void 取消_商家身份401() throws Exception {
        merchantLogin();

        int status = mockMvc.perform(post("/order/buyer/cancel/1"))
                .andReturn().getResponse().getStatus();

        assertEquals(401, status, "商家身份访问买家取消接口应返回 401");
        verifyNoInteractions(orderService);
    }
}
