package com.ysh.b2cmall.product.web;

import com.ysh.b2cmall.product.bean.CurrentEmployeeBean;
import com.ysh.b2cmall.product.component.RequestHolderComponent;
import com.ysh.b2cmall.product.service.DownstreamServiceException;
import com.ysh.b2cmall.product.service.SkuService;
import com.ysh.b2cmall.product.web.advice.GlobalExceptionHandler;
import com.ysh.b2cmall.product.web.controller.ProductController;
import com.ysh.b2cmall.product.web.interceptor.LoginInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/**
 * 删除接口的下游容错契约测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-product-list.md 功能上下文 7.3.4：
 * order-service 不可用 -> 删除返回 503 + 中文消息，宁可拒绝删除也不放行。
 *
 * 用例分类：
 * 1. 字段级校验：503 响应含中文原因（非笼统 500）
 * 2. 类型与格式：503 + BaseResponseVO JSON
 * 3. 重复创建：下游持续故障时重复请求每次均 503（不放行）
 * 4. 非法输入：下游异常不得映射为 400/404 误导前端
 */
class ProductDeleteFallbackControllerTest {

    private RequestHolderComponent requestHolder;
    private SkuService skuService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        requestHolder = mock(RequestHolderComponent.class);
        skuService = mock(SkuService.class);
        when(requestHolder.get()).thenReturn(loginBean(3));
        mockMvc = MockMvcBuilders.standaloneSetup(new ProductController(skuService, requestHolder))
                .addInterceptors(new LoginInterceptor(requestHolder))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private CurrentEmployeeBean loginBean(Integer shopId) {
        CurrentEmployeeBean bean = new CurrentEmployeeBean();
        bean.setEmployeeId(7);
        bean.setShopId(shopId);
        bean.setUsername("tom");
        return bean;
    }

    // ============ 1+2. 下游故障 -> 503 + 中文消息 ============

    @Test
    void 下游故障_返回503中文消息() throws Exception {
        doThrow(new DownstreamServiceException("订单服务暂不可用，请稍后重试"))
                .when(skuService).deleteSku(eq(3), eq(10));

        MvcResult result = mockMvc.perform(delete("/product/delete/10")).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(503, response.getStatus(), "下游不可用应返回 503（区别于真系统故障 500）");
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("订单服务暂不可用"), "503 消息应含「订单服务暂不可用」，实际：" + body);
    }

    // ============ 3. 重复创建：持续故障每次均 503 ============

    @Test
    void 下游持续故障_重复请求均503不放行() throws Exception {
        doThrow(new DownstreamServiceException("订单服务暂不可用，请稍后重试"))
                .when(skuService).deleteSku(eq(3), eq(10));

        MvcResult first = mockMvc.perform(delete("/product/delete/10")).andReturn();
        MvcResult second = mockMvc.perform(delete("/product/delete/10")).andReturn();

        assertEquals(503, first.getResponse().getStatus(), "首次故障应 503");
        assertEquals(503, second.getResponse().getStatus(), "重复故障仍应 503（不放行）");
    }

    // ============ 4. 非法输入：下游故障不得映射为 400/404 ============

    @Test
    void 下游故障_不得映射为400或404() throws Exception {
        doThrow(new DownstreamServiceException("订单服务暂不可用，请稍后重试"))
                .when(skuService).deleteSku(eq(3), eq(10));

        MvcResult result = mockMvc.perform(delete("/product/delete/10")).andReturn();
        int status = result.getResponse().getStatus();

        assertTrue(status != 400 && status != 404,
                "下游故障不得映射为 400/404 误导前端（实际：" + status + "）");
    }
}
