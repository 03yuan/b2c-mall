package com.ysh.order.web;

import com.ysh.order.bean.CurrentEmployeeBean;
import com.ysh.order.component.RequestHolderComponent;
import com.ysh.order.service.OrderService;
import com.ysh.order.web.advice.GlobalExceptionHandler;
import com.ysh.order.web.vo.RefundInfoVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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

/**
 * 退款信息 GET /order/{orderId}/refund 的 Controller 契约测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 9.3.1：
 * - 有退款单 -> 返回详情；无退款单 -> 「暂无售后」（200 而非 404/500）；他店/不存在 -> 404；无商家身份 -> 401
 *   （Fail 边界：无退款单返回 404/500 -> 前端无法区分「无售后」与「接口异常」）
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：合法请求透传登录 shopId 与 orderId，返回 RefundInfoVO
 * 2. 类型与格式：无退款单 -> 200（data null，不报错）
 * 3. 重复创建：同请求重复提交调用两次且响应一致
 * 4. 非法输入：他店/不存在 -> 404；无商家身份 -> 401 且 Service 零调用
 *
 * standalone MockMvc + mock OrderService；被测物为 OrderController 的退款信息端点。
 */
class OrderRefundInfoControllerTest {

    private static final Integer LOGIN_SHOP_ID = 5;
    private static final int ORDER_ID = 88;

    private OrderService orderService;
    private RequestHolderComponent requestHolder;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        requestHolder = mock(RequestHolderComponent.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new OrderController(orderService, requestHolder))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private void merchantLogin() {
        CurrentEmployeeBean current = new CurrentEmployeeBean();
        current.setEmployeeId(7);
        current.setShopId(LOGIN_SHOP_ID);
        current.setUsername("tom");
        when(requestHolder.get()).thenReturn(current);
    }

    private int getRefund(int orderId) throws Exception {
        return mockMvc.perform(get("/order/" + orderId + "/refund"))
                .andReturn().getResponse().getStatus();
    }

    // ============ 1. 字段级校验：合法请求透传 + 返回详情 ============

    @Test
    void 合法请求_透传登录shopId与orderId() throws Exception {
        merchantLogin();
        RefundInfoVO info = new RefundInfoVO();
        info.setStatus("PROCESSING");
        info.setAmount(new BigDecimal("39.80"));
        info.setCreateTime(LocalDateTime.of(2026, 10, 20, 10, 0, 0));
        when(orderService.getRefundInfo(eq(ORDER_ID), eq(LOGIN_SHOP_ID))).thenReturn(info);

        int status = getRefund(ORDER_ID);

        assertEquals(200, status, "合法退款信息请求应返回 200");
        verify(orderService, times(1)).getRefundInfo(eq(ORDER_ID), eq(LOGIN_SHOP_ID));
    }

    // ============ 2. 类型与格式：无退款单 -> 200 不报错 ============

    @Test
    void 无退款单_返回200而非404或500() throws Exception {
        merchantLogin();
        when(orderService.getRefundInfo(eq(ORDER_ID), eq(LOGIN_SHOP_ID))).thenReturn(null);

        int status = getRefund(ORDER_ID);

        assertEquals(200, status, "无退款单应返回 200（暂无售后），不得 404/500");
    }

    // ============ 3. 重复创建：同请求重复提交 ============

    @Test
    void 同请求重复提交_两次均调用且响应一致() throws Exception {
        merchantLogin();
        when(orderService.getRefundInfo(eq(ORDER_ID), eq(LOGIN_SHOP_ID))).thenReturn(new RefundInfoVO());

        int first = getRefund(ORDER_ID);
        int second = getRefund(ORDER_ID);

        assertEquals(first, second, "同请求重复提交响应状态应一致");
        verify(orderService, times(2)).getRefundInfo(eq(ORDER_ID), eq(LOGIN_SHOP_ID));
    }

    // ============ 4. 非法输入：他店/不存在 / 无商家身份 ============

    @Test
    void 他店或不存在订单_映射为404() throws Exception {
        merchantLogin();
        doThrow(new IllegalArgumentException("订单不存在: " + ORDER_ID))
                .when(orderService).getRefundInfo(eq(ORDER_ID), any());

        int status = getRefund(ORDER_ID);

        assertEquals(404, status, "他店/不存在订单应为 404");
    }

    @Test
    void 无商家身份_返回401且不调用Service() throws Exception {
        when(requestHolder.get()).thenReturn(null);

        int status = getRefund(ORDER_ID);

        assertEquals(401, status, "无商家登录态（shopId 为空）应为 401");
        verifyNoInteractions(orderService);
    }
}
