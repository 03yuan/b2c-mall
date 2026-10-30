package com.ysh.b2cmall.product.web;

import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;
import com.ysh.b2cmall.product.bean.CurrentEmployeeBean;
import com.ysh.b2cmall.product.component.RequestHolderComponent;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/**
 * DELETE /product/delete/{skuId} 的契约测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-product-list.md 功能上下文 7.3.3：
 * 已产生订单 -> 400「已产生订单的商品禁止删除」；无订单 -> 删除；shopId 隔离。
 *
 * 用例分类：
 * 1. 字段级校验：skuId 路径变量透传 Service，shopId 取登录上下文
 * 2. 类型与格式：成功 200 + BaseResponseVO JSON
 * 3. 重复创建：重复删除第二次 -> 404（Service 抛「不存在」）
 * 4. 非法输入：已产生订单 -> 400 中文消息；他店/不存在 -> 404；越权 shopId 忽略
 */
class ProductDeleteControllerTest {

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

    // ============ 1+2. 成功 200 + 参数透传 ============

    @Test
    void 删除成功_200且参数透传() throws Exception {
        MvcResult result = mockMvc.perform(delete("/product/delete/10")).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(200, response.getStatus(), "删除成功应返回 200");
        JSONObject body = JSONUtil.parseObj(response.getContentAsString(StandardCharsets.UTF_8));
        assertTrue(body.containsKey("status"), "响应应为 BaseResponseVO JSON");
        verify(skuService).deleteSku(3, 10);
    }

    // ============ 4. 非法输入：已产生订单 -> 400 ============

    @Test
    void 已产生订单_返回400中文消息() throws Exception {
        doThrow(new IllegalArgumentException("已产生订单的商品禁止删除"))
                .when(skuService).deleteSku(eq(3), eq(10));

        MvcResult result = mockMvc.perform(delete("/product/delete/10")).andReturn();

        assertEquals(400, result.getResponse().getStatus(), "已产生订单应返回 400");
        assertTrue(result.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("已产生订单"),
                "400 消息应含「已产生订单」");
    }

    // ============ 3. 重复创建 + 4. 他店/不存在 -> 404 ============

    @Test
    void 他店或重复删除_返回404中文消息() throws Exception {
        doThrow(new IllegalArgumentException("商品不存在"))
                .when(skuService).deleteSku(eq(3), eq(20));

        MvcResult result = mockMvc.perform(delete("/product/delete/20")).andReturn();

        assertEquals(404, result.getResponse().getStatus(), "他店/不存在应返回 404");
        assertTrue(result.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("不存在"),
                "404 消息应含「不存在」");
    }

    // ============ 4. 非法输入：越权 shopId 忽略 ============

    @Test
    void 越权shopId参数被忽略() throws Exception {
        mockMvc.perform(delete("/product/delete/10").param("shopId", "99")).andReturn();

        verify(skuService).deleteSku(3, 10);
    }
}
