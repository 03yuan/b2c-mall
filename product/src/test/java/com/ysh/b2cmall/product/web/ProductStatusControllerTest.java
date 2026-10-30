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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * POST /product/status 上架/下架的契约测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-product-list.md 功能上下文 7.2.1：
 * 设为目标状态（双向），shopId 隔离，重复操作幂等成功。
 *
 * 用例分类：
 * 1. 字段级校验：skuId/status 参数原样透传 Service，shopId 取登录上下文
 * 2. 类型与格式：成功返回 200 + BaseResponseVO JSON；业务异常 -> 400 中文消息
 * 3. 重复创建：重复请求幂等一致
 * 4. 非法输入：他店/不存在 -> 404 中文消息；越权 shopId 忽略
 */
class ProductStatusControllerTest {

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

    // ============ 1. 字段级校验：双向参数透传（表格驱动） ============

    /**
     * 列：用例名、skuId、目标状态
     */
    static Stream<Arguments> switchCases() {
        return Stream.of(
                arguments("上架商品执行下架", 10, 0),
                arguments("下架商品执行上架", 11, 1)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("switchCases")
    void 上下架_参数原样透传Service(String caseName, Integer skuId, Integer status) throws Exception {
        MvcResult result = mockMvc.perform(post("/product/status")
                        .param("skuId", String.valueOf(skuId))
                        .param("status", String.valueOf(status)))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus(), caseName + "：应返回 200");
        assertTrue(result.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("\"status\""),
                caseName + "：响应应为 BaseResponseVO JSON");
        verify(skuService).updateStatus(3, skuId, status);
    }

    // ============ 2. 类型与格式：业务异常 -> 400 中文消息 ============

    @Test
    void 业务校验失败_返回400中文消息() throws Exception {
        doThrow(new IllegalArgumentException("状态必须为 0（下架）或 1（上架）"))
                .when(skuService).updateStatus(eq(3), eq(10), eq(2));

        MvcResult result = mockMvc.perform(post("/product/status")
                        .param("skuId", "10").param("status", "2"))
                .andReturn();

        assertEquals(400, result.getResponse().getStatus(), "业务校验失败应返回 400");
        assertTrue(result.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("状态"),
                "400 消息应含中文原因");
    }

    // ============ 4. 非法输入：他店/不存在 -> 404 ============

    @Test
    void 他店或不存在_返回404中文消息() throws Exception {
        doThrow(new IllegalArgumentException("商品不存在"))
                .when(skuService).updateStatus(eq(3), eq(20), eq(0));

        MvcResult result = mockMvc.perform(post("/product/status")
                        .param("skuId", "20").param("status", "0"))
                .andReturn();

        assertEquals(404, result.getResponse().getStatus(), "他店/不存在应返回 404");
        assertTrue(result.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("不存在"),
                "404 消息应含「不存在」");
    }

    // ============ 3. 重复创建：重复请求幂等一致 ============

    @Test
    void 重复下架_响应一致() throws Exception {
        String first = mockMvc.perform(post("/product/status")
                        .param("skuId", "10").param("status", "0"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String second = mockMvc.perform(post("/product/status")
                        .param("skuId", "10").param("status", "0"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertEquals(first, second, "重复请求响应必须一致");
        assertTrue(first.contains("\"status\""), "响应应为 BaseResponseVO JSON");
        verify(skuService, times(2)).updateStatus(3, 10, 0);
    }

    // ============ 4. 非法输入：越权 shopId 忽略 ============

    @Test
    void 越权shopId参数被忽略() throws Exception {
        mockMvc.perform(post("/product/status")
                        .param("skuId", "10").param("status", "0").param("shopId", "99"))
                .andReturn();

        verify(skuService).updateStatus(3, 10, 0);
    }
}
