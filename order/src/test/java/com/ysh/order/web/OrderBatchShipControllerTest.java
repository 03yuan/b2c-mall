package com.ysh.order.web;

import com.ysh.order.bean.CurrentEmployeeBean;
import com.ysh.order.component.RequestHolderComponent;
import com.ysh.order.service.OrderService;
import com.ysh.order.web.advice.GlobalExceptionHandler;
import com.ysh.order.web.request.BatchShipRequest;
import com.ysh.order.web.vo.BatchShipResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * POST /order/batch-ship 的 Controller 契约测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 7.2：
 * - 7.2.1 items[{orderId, logisticsNo, logisticsType}] 逐条独立处理，成功/失败汇总返回
 * - 7.2.2 入参校验：items 为空 -> 400；无商家身份 -> 401（单条缺物流单号为 Service 层职责，Controller 不拦截）
 *   （Fail 边界：空 items 返回 200 成功 -> 前端误以为发货完成；信任前端 shopId -> 越权发货）
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：合法请求透传登录 shopId 与 items，汇总结果（success/failed 与中文原因）原样返回
 * 2. 类型与格式：items 为空数组 / 缺失 -> 400
 * 3. 重复创建：同请求重复提交调用两次且响应一致（幂等语义由 Service 保证，Controller 不缓存）
 * 4. 非法输入：items 为空 -> 400 中文原因；无商家身份 -> 401；以上 Service 零调用
 *
 * standalone MockMvc + mock OrderService；被测物为 OrderController 的入参绑定与校验。
 */
class OrderBatchShipControllerTest {

    private static final Integer LOGIN_SHOP_ID = 5;

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

    private BatchShipRequest.Item item(int orderId, String logisticsNo, String logisticsType) {
        BatchShipRequest.Item it = new BatchShipRequest.Item();
        it.setOrderId(orderId);
        it.setLogisticsNo(logisticsNo);
        it.setLogisticsType(logisticsType);
        return it;
    }

    private MvcResult postJson(String body) throws Exception {
        return mockMvc.perform(post("/order/batch-ship")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    // ============ 1. 字段级校验：合法请求透传 + 汇总响应 ============

    @Test
    void 合法请求_透传登录shopId与items并返回汇总() throws Exception {
        merchantLogin();
        BatchShipResult result = new BatchShipResult();
        result.setSuccess(1);
        BatchShipResult.FailedItem failed = new BatchShipResult.FailedItem();
        failed.setOrderId(2);
        failed.setReason("订单已发货，不能重复发货");
        result.setFailed(Arrays.asList(failed));
        when(orderService.batchShip(anyList(), eq(LOGIN_SHOP_ID), any())).thenReturn(result);

        MvcResult res = postJson("{\"items\":["
                + "{\"orderId\":1,\"logisticsNo\":\"SF1\",\"logisticsType\":\"EXPRESS\"},"
                + "{\"orderId\":2,\"logisticsNo\":\"SF2\",\"logisticsType\":\"EXPRESS\"}]}");

        assertEquals(200, res.getResponse().getStatus(), "合法批量发货请求应返回 200");
        // 透传 items + 登录 shopId（请求体中不含 shopId）
        verify(orderService, times(1)).batchShip(anyList(), eq(LOGIN_SHOP_ID), any());

        String json = res.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"success\":1"), "响应应含 success=1，实际：" + json);
        assertTrue(json.contains("\"orderId\":2"), "失败列表应含 orderId=2，实际：" + json);
        assertTrue(json.contains("订单已发货，不能重复发货"), "失败列表应含中文原因，实际：" + json);
    }

    // ============ 2. 类型与格式：items 为空数组 / 缺失 -> 400 ============

    /**
     * 列：用例名、请求体 JSON、期望响应消息关键词
     */
    static Stream<Arguments> emptyItemsCases() {
        return Stream.of(
                arguments("items为空数组", "{\"items\":[]}", "列表"),
                arguments("items为null", "{\"items\":null}", "列表"),
                arguments("items字段缺失", "{}", "列表")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("emptyItemsCases")
    void 空items_返回400且不调用Service(String caseName, String body, String expectedKeyword)
            throws Exception {
        merchantLogin();

        MvcResult res = postJson(body);

        assertEquals(400, res.getResponse().getStatus(), caseName + "：空 items 应为 400");
        String content = res.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(content.contains(expectedKeyword),
                caseName + "：响应消息应含「" + expectedKeyword + "」，实际：" + content);
        verifyNoInteractions(orderService);
    }

    // ============ 3. 重复创建：同请求重复提交 ============

    @Test
    void 同请求重复提交_两次均调用且响应一致() throws Exception {
        merchantLogin();
        BatchShipResult result = new BatchShipResult();
        result.setSuccess(2);
        when(orderService.batchShip(anyList(), eq(LOGIN_SHOP_ID), any())).thenReturn(result);
        String body = "{\"items\":["
                + "{\"orderId\":1,\"logisticsNo\":\"SF1\",\"logisticsType\":\"EXPRESS\"},"
                + "{\"orderId\":2,\"logisticsNo\":\"SF2\",\"logisticsType\":\"CITY\"}]}";

        MvcResult first = postJson(body);
        MvcResult second = postJson(body);

        assertEquals(first.getResponse().getStatus(), second.getResponse().getStatus(),
                "同请求重复提交响应状态应一致");
        assertEquals(first.getResponse().getContentAsString(StandardCharsets.UTF_8),
                second.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "同请求重复提交响应体应一致");
        verify(orderService, times(2)).batchShip(anyList(), eq(LOGIN_SHOP_ID), any());
    }

    // ============ 4. 非法输入：无商家身份 / 空 items ============

    @Test
    void 无商家身份_返回401且不调用Service() throws Exception {
        when(requestHolder.get()).thenReturn(null);

        MvcResult res = postJson("{\"items\":["
                + "{\"orderId\":1,\"logisticsNo\":\"SF1\",\"logisticsType\":\"EXPRESS\"}]}");

        assertEquals(401, res.getResponse().getStatus(), "无商家登录态（shopId 为空）应为 401");
        verifyNoInteractions(orderService);
    }
}
