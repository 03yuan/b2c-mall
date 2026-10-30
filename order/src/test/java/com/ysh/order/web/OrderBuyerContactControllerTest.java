package com.ysh.order.web;

import com.ysh.order.bean.CurrentEmployeeBean;
import com.ysh.order.component.RequestHolderComponent;
import com.ysh.order.service.OrderService;
import com.ysh.order.web.advice.GlobalExceptionHandler;
import com.ysh.order.web.vo.BuyerContactVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/**
 * 联系买家 GET /order/{orderId}/buyer-contact 的 Controller 契约测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 9.2.1：
 * - shopId 强制取自登录上下文，他店/不存在 -> 404；无商家身份 -> 401
 *   （Fail 边界：越权获取买家联系方式）
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：合法请求透传登录 shopId 与 orderId，返回 BuyerContactVO
 * 2. 类型与格式：业务异常按 404 映射（消息含「不存在」）
 * 3. 重复创建：同请求重复提交调用两次且响应一致
 * 4. 非法输入：他店/不存在 -> 404；无商家身份 -> 401 且 Service 零调用
 *
 * standalone MockMvc + mock OrderService；被测物为 OrderController 的联系买家端点。
 */
class OrderBuyerContactControllerTest {

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

    private int getContact(int orderId) throws Exception {
        return mockMvc.perform(get("/order/" + orderId + "/buyer-contact"))
                .andReturn().getResponse().getStatus();
    }

    // ============ 1. 字段级校验：合法请求透传 ============

    @Test
    void 合法请求_透传登录shopId与orderId() throws Exception {
        merchantLogin();
        BuyerContactVO contact = new BuyerContactVO();
        contact.setBuyerUsername("buyer_a");
        contact.setMaskedPhone("138****5678");
        when(orderService.getBuyerContact(eq(ORDER_ID), eq(LOGIN_SHOP_ID))).thenReturn(contact);

        int status = getContact(ORDER_ID);

        assertEquals(200, status, "合法联系买家请求应返回 200");
        verify(orderService, times(1)).getBuyerContact(eq(ORDER_ID), eq(LOGIN_SHOP_ID));
    }

    // ============ 2. 类型与格式：业务异常映射 404 ============

    @Test
    void 他店或不存在订单_映射为404() throws Exception {
        merchantLogin();
        doThrow(new IllegalArgumentException("订单不存在: " + ORDER_ID))
                .when(orderService).getBuyerContact(eq(ORDER_ID), any());

        int status = getContact(ORDER_ID);

        assertEquals(404, status, "他店/不存在订单应为 404");
    }

    // ============ 3. 重复创建：同请求重复提交 ============

    @Test
    void 同请求重复提交_两次均调用且响应一致() throws Exception {
        merchantLogin();
        when(orderService.getBuyerContact(eq(ORDER_ID), eq(LOGIN_SHOP_ID))).thenReturn(new BuyerContactVO());

        int first = getContact(ORDER_ID);
        int second = getContact(ORDER_ID);

        assertEquals(first, second, "同请求重复提交响应状态应一致");
        verify(orderService, times(2)).getBuyerContact(eq(ORDER_ID), eq(LOGIN_SHOP_ID));
    }

    // ============ 4. 非法输入：无商家身份 ============

    @Test
    void 无商家身份_返回401且不调用Service() throws Exception {
        when(requestHolder.get()).thenReturn(null);

        int status = getContact(ORDER_ID);

        assertEquals(401, status, "无商家登录态（shopId 为空）应为 401");
        verifyNoInteractions(orderService);
    }
}
