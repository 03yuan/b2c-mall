package com.ysh.order.web;

import com.ysh.order.bean.CurrentEmployeeBean;
import com.ysh.order.component.RequestHolderComponent;
import com.ysh.order.service.OrderService;
import com.ysh.order.web.advice.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * POST /order/ship/{orderId} 的 Controller 契约测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 7.1.2（发货参数校验与店铺隔离）：
 * - logisticsNo 非空、logisticsType 在枚举内（EXPRESS 普通快递 / CITY 同城配送）-> 违反 400
 * - shopId 强制取自登录上下文（商家身份），无商家身份 -> 401
 * - 非法入参一律 Service 零调用（不得先查库再校验）
 *   （Fail 边界：空单号发货成功 -> 商家无单号履约；信任前端 shopId -> 越权发货）
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：合法请求透传 orderId/单号/类型/登录 shopId，且状态流转异常按 400/404 返回
 * 2. 类型与格式：物流类型小写/带空格视为合法；缺失必填参数 -> 400
 * 3. 重复创建：同请求重复提交调用两次，响应一致（幂等语义由状态机保证，Controller 不缓存）
 * 4. 非法输入：空白单号、非法物流类型 -> 400 中文原因；无商家身份 -> 401；以上 Service 零调用
 *
 * standalone MockMvc + mock OrderService；被测物为 OrderController 的入参绑定与校验。
 */
class OrderShipControllerTest {

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

    private int ship(String orderId, String logisticsNo, String logisticsType) throws Exception {
        return mockMvc.perform(post("/order/ship/" + orderId)
                        .param("logisticsNo", logisticsNo)
                        .param("logisticsType", logisticsType))
                .andReturn().getResponse().getStatus();
    }

    // ============ 1. 字段级校验：合法请求透传 + 业务异常映射 ============

    @Test
    void 合法请求_透传登录shopId与物流信息() throws Exception {
        merchantLogin();

        int status = ship(String.valueOf(ORDER_ID), "SF1234567890", "EXPRESS");

        assertEquals(200, status, "合法发货请求应返回 200");
        verify(orderService, times(1)).ship(eq(ORDER_ID), eq("SF1234567890"), eq("EXPRESS"),
                eq(LOGIN_SHOP_ID), any());
    }

    @Test
    void 他店订单_映射为404() throws Exception {
        merchantLogin();
        doThrow(new IllegalArgumentException("订单不存在: " + ORDER_ID))
                .when(orderService).ship(eq(ORDER_ID), any(), any(), any(), any());

        int status = ship(String.valueOf(ORDER_ID), "SF1", "EXPRESS");

        assertEquals(404, status, "他店/不存在订单应为 404");
    }

    @Test
    void 非待发货状态_映射为400() throws Exception {
        merchantLogin();
        doThrow(new IllegalStateException("当前状态 PENDING 不能发货"))
                .when(orderService).ship(eq(ORDER_ID), any(), any(), any(), any());

        int status = ship(String.valueOf(ORDER_ID), "SF1", "EXPRESS");

        assertEquals(400, status, "状态机非法流转应为 400");
    }

    // ============ 2. 类型与格式：物流类型归一化与缺失参数 ============

    /**
     * 列：用例名、物流类型入参
     */
    static Stream<Arguments> acceptedTypeCases() {
        return Stream.of(
                arguments("普通快递EXPRESS", "EXPRESS"),
                arguments("同城配送CITY", "CITY"),
                arguments("小写express", "express"),
                arguments("带空格CITY", " CITY ")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedTypeCases")
    void 合法物流类型_请求被受理(String caseName, String inputType) throws Exception {
        merchantLogin();

        int status = ship(String.valueOf(ORDER_ID), "SF1", inputType);

        assertEquals(200, status, caseName + "：应受理");
        verify(orderService, times(1)).ship(eq(ORDER_ID), eq("SF1"), any(), eq(LOGIN_SHOP_ID), any());
    }

    @Test
    void 缺少物流单号参数_返回400() throws Exception {
        merchantLogin();

        int status = mockMvc.perform(post("/order/ship/" + ORDER_ID)
                        .param("logisticsType", "EXPRESS"))
                .andReturn().getResponse().getStatus();

        assertEquals(400, status, "缺少必填参数 logisticsNo 应为 400");
        verifyNoInteractions(orderService);
    }

    // ============ 3. 重复创建：同请求重复提交 ============

    @Test
    void 同请求重复提交_两次均调用且响应一致() throws Exception {
        merchantLogin();

        int first = ship(String.valueOf(ORDER_ID), "SF1", "EXPRESS");
        int second = ship(String.valueOf(ORDER_ID), "SF1", "EXPRESS");

        assertEquals(first, second, "同请求重复提交响应应一致");
        verify(orderService, times(2)).ship(eq(ORDER_ID), eq("SF1"), eq("EXPRESS"),
                eq(LOGIN_SHOP_ID), any());
    }

    // ============ 4. 非法输入：单号/类型/身份 ============

    /**
     * 列：用例名、物流单号入参、期望响应消息片段
     */
    static Stream<Arguments> invalidLogisticsNoCases() {
        return Stream.of(
                arguments("单号为空串", "", "物流单号"),
                arguments("单号为空白", "   ", "物流单号")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidLogisticsNoCases")
    void 空白物流单号_返回400且不调用Service(String caseName, String logisticsNo, String expectedMessage)
            throws Exception {
        merchantLogin();

        String body = mockMvc.perform(post("/order/ship/" + ORDER_ID)
                        .param("logisticsNo", logisticsNo)
                        .param("logisticsType", "EXPRESS"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(body.contains(expectedMessage), caseName + "：响应消息应含「" + expectedMessage
                + "」，实际：" + body);
        verifyNoInteractions(orderService);
    }

    /**
     * 列：用例名、物流类型入参
     */
    static Stream<Arguments> invalidTypeCases() {
        return Stream.of(
                arguments("空串", ""),
                arguments("不在枚举内SAME_DAY", "SAME_DAY"),
                arguments("拼写错误EXPRES", "EXPRES")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTypeCases")
    void 非法物流类型_返回400且不调用Service(String caseName, String logisticsType) throws Exception {
        merchantLogin();

        String body = mockMvc.perform(post("/order/ship/" + ORDER_ID)
                        .param("logisticsNo", "SF1")
                        .param("logisticsType", logisticsType))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(body.contains("物流类型"), caseName + "：响应消息应含「物流类型」，实际：" + body);
        verifyNoInteractions(orderService);
    }

    @Test
    void 无商家身份_返回401且不调用Service() throws Exception {
        when(requestHolder.get()).thenReturn(null);

        int status = ship(String.valueOf(ORDER_ID), "SF1", "EXPRESS");

        assertEquals(401, status, "无商家登录态（shopId 为空）应为 401");
        verifyNoInteractions(orderService);
    }
}
