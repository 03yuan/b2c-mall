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
 * 买家去支付 Controller 契约测试（TDD 红阶段）。
 * 对应 .tdd/task/mall-order-manage.md 功能上下文 7.2（Web 层 - 接口契约 POST /order/buyer/pay/{orderId}?payType=xxx）：
 * - 7.2.1 payType 必填（缺失 -> 400 中文原因）；本人 PENDING -> 200；他人/不存在 -> 404；未登录/商家 token -> 401
 *
 * 买家身份强制：username 取自登录上下文；商家身份/无登录态 -> 401。
 * standalone MockMvc + mock OrderService；被测物为 BuyerOrderController。
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：透传登录 username + orderId + payType
 * 2. 类型与格式：200 空 body；payType 大小写原样透传
 * 3. 重复创建：重复提交均调用
 * 4. 非法输入：他人/不存在 404，payType 缺失 400，无登录态/商家 401
 */
class BuyerPayControllerTest {

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

    // ============ 1. 字段级校验：透传登录 username + orderId + payType ============

    @Test
    void 支付_透传登录username与payType() throws Exception {
        buyerLogin();

        int status = mockMvc.perform(post("/order/buyer/pay/1").param("payType", "BALANCE"))
                .andReturn().getResponse().getStatus();

        assertEquals(200, status, "本人支付应返回 200");
        verify(orderService, times(1)).payByBuyer(1, "BALANCE", LOGIN_USERNAME);
    }

    // ============ 2. 类型与格式：orderId 与 payType 透传正确 ============

    @Test
    void 支付_orderId与payType透传正确() throws Exception {
        buyerLogin();

        mockMvc.perform(post("/order/buyer/pay/99").param("payType", "ALIPAY"))
                .andReturn().getResponse().getStatus();

        verify(orderService, times(1)).payByBuyer(99, "ALIPAY", LOGIN_USERNAME);
    }

    @Test
    void 支付_payType原样透传不转大小写() throws Exception {
        buyerLogin();

        mockMvc.perform(post("/order/buyer/pay/1").param("payType", "balance"))
                .andReturn().getResponse().getStatus();

        verify(orderService, times(1)).payByBuyer(1, "balance", LOGIN_USERNAME);
    }

    // ============ 3. 重复创建：重复提交均调用 ============

    @Test
    void 支付_重复提交_两次均调用() throws Exception {
        buyerLogin();

        int first = mockMvc.perform(post("/order/buyer/pay/1").param("payType", "BALANCE"))
                .andReturn().getResponse().getStatus();
        int second = mockMvc.perform(post("/order/buyer/pay/1").param("payType", "BALANCE"))
                .andReturn().getResponse().getStatus();

        assertEquals(first, second, "重复提交响应状态一致");
        verify(orderService, times(2)).payByBuyer(1, "BALANCE", LOGIN_USERNAME);
    }

    // ============ 4. 非法输入：他人/不存在 404，payType 缺失 400，401 ============

    @Test
    void 支付_他人订单404() throws Exception {
        buyerLogin();
        doThrow(new IllegalArgumentException("订单不存在: 1"))
                .when(orderService).payByBuyer(eq(1), eq("BALANCE"), eq(LOGIN_USERNAME));

        int status = mockMvc.perform(post("/order/buyer/pay/1").param("payType", "BALANCE"))
                .andReturn().getResponse().getStatus();

        assertEquals(404, status, "支付他人订单应返回 404");
    }

    @Test
    void 支付_payType缺失_返回400() throws Exception {
        buyerLogin();

        int status = mockMvc.perform(post("/order/buyer/pay/1"))
                .andReturn().getResponse().getStatus();

        assertEquals(400, status, "payType 缺失应返回 400");
        verifyNoInteractions(orderService);
    }

    @Test
    void 支付_无登录态401() throws Exception {
        when(requestHolder.get()).thenReturn(null);

        int status = mockMvc.perform(post("/order/buyer/pay/1").param("payType", "BALANCE"))
                .andReturn().getResponse().getStatus();

        assertEquals(401, status, "无登录态应返回 401");
        verifyNoInteractions(orderService);
    }

    @Test
    void 支付_商家身份401() throws Exception {
        merchantLogin();

        int status = mockMvc.perform(post("/order/buyer/pay/1").param("payType", "BALANCE"))
                .andReturn().getResponse().getStatus();

        assertEquals(401, status, "商家身份访问买家支付接口应返回 401");
        verifyNoInteractions(orderService);
    }
}
