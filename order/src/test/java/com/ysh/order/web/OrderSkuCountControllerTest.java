package com.ysh.order.web;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ysh.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * GET /order/sku/{skuId}/count 的契约测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-product-list.md 功能上下文 7.3.1：
 * order-service 新增订单存在性统计接口，返回该商品的订单条数（shopId 隔离）。
 *
 * 用例分类：
 * 1. 字段级校验：响应 data 含 count 且取值正确；skuId 路径变量透传 Service
 * 2. 类型与格式：200 + BaseResponseVO JSON
 * 3. 重复创建：重复请求响应一致
 * 4. 非法输入：无订单商品 count=0
 */
class OrderSkuCountControllerTest {

    private OrderService orderService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        OrderController controller = new OrderController();
        ReflectionTestUtils.setField(controller, "orderService", orderService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ============ 1. 字段级校验 + 2. 类型与格式 ============

    @Test
    void 有订单商品_返回计数() throws Exception {
        when(orderService.countBySkuId(1, 1)).thenReturn(2);

        MvcResult result = mockMvc.perform(get("/order/sku/1/count")).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(200, response.getStatus(), "应返回 200");
        String bodyStr = response.getContentAsString(StandardCharsets.UTF_8);
        JSONObject body = JSONUtil.parseObj(bodyStr);
        assertTrue(body.containsKey("data"), "响应应为 BaseResponseVO JSON（含 data）");
        assertEquals(2, body.getJSONObject("data").getInt("count").intValue(), "count 应为 2");
        verify(orderService).countBySkuId(1, 1);
    }

    // ============ 4. 非法输入：无订单商品 count=0 ============

    @Test
    void 无订单商品_返回0() throws Exception {
        when(orderService.countBySkuId(2, 1)).thenReturn(0);

        MvcResult result = mockMvc.perform(get("/order/sku/2/count")).andReturn();

        assertEquals(200, result.getResponse().getStatus(), "无订单也应 200");
        JSONObject body = JSONUtil.parseObj(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(0, body.getJSONObject("data").getInt("count").intValue(), "无订单 count 应为 0 而非伪造数值");
    }

    // ============ 3. 重复创建：重复请求响应一致 ============

    @Test
    void 重复请求_响应一致() throws Exception {
        when(orderService.countBySkuId(1, 1)).thenReturn(2);

        String first = mockMvc.perform(get("/order/sku/1/count"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String second = mockMvc.perform(get("/order/sku/1/count"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertEquals(first, second, "重复请求响应必须一致");
        assertTrue(first.contains("\"count\""), "响应应含 count 字段");
    }
}
