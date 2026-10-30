package com.ysh.b2cmall.product.web;

import cn.hutool.json.JSONUtil;
import com.ysh.b2cmall.product.bean.CurrentEmployeeBean;
import com.ysh.b2cmall.product.component.RequestHolderComponent;
import com.ysh.b2cmall.product.web.advice.GlobalExceptionHandler;
import com.ysh.b2cmall.product.web.controller.ProductBatchController;
import com.ysh.b2cmall.product.web.controller.ProductController;
import com.ysh.b2cmall.product.web.controller.ProductImportExportController;
import com.ysh.b2cmall.product.web.controller.ProductListController;
import com.ysh.b2cmall.product.web.interceptor.LoginInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 店铺商品管理新增接口的登录契约测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-product-list.md 功能上下文 2.1（Filter/组件层 - 登录态注入）：
 * - 2.1.1 所有新增商品接口（list/categories/import/export/batch/detail/update/status/delete）
 *   无有效 token 返回 401（Fail 边界：未登录可看/改商品）
 * - 2.1.2 业务代码只从登录上下文（RequestHolderComponent）取 shopId，shopId 为 null 时拒绝
 *   （Fail 边界：shopId 为 null 时查询条件失效，查出全店数据）
 *
 * 用例分类（表格驱动，适配登录契约校验）：
 * 1. 字段级校验：登录态字段（shopId 等）被组件层从登录上下文正确读取；shopId 缺失拒绝
 * 2. 类型与格式：401 响应契约（HTTP 401 + JSON + 中文消息）
 * 3. 重复创建：同一登录态重复请求幂等放行、登录态被稳定读取（不串态）
 * 4. 非法输入：无登录态（无有效 token 的兜底语义）一律 401
 *
 * 说明：TokenFilter 的 Bearer 解析/Redis 查找/白名单为既有复用组件（其行为为回归语义，
 * 请求不带头会被它先行 401），本组测试不重复锁定，改为锁定新增接口自身的登录契约——
 * 即使误加白名单或绕过网关直连，接口层也必须拒绝未登录请求。
 * 登录态 mock 自 RequestHolderComponent（TokenFilter 注入的内容与 Redis 一致）。
 */
class ProductAuthTest {

    private RequestHolderComponent requestHolder;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        requestHolder = mock(RequestHolderComponent.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ProductListController(),
                        new ProductBatchController(),
                        new ProductImportExportController(),
                        new ProductController())
                .addInterceptors(new LoginInterceptor(requestHolder))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** 受登录保护的全部新增接口：用例名、请求方法、URL */
    static Stream<Arguments> protectedEndpoints() {
        return Stream.of(
                arguments("商品列表", "GET", "/product/list"),
                arguments("商品分类下拉", "GET", "/product/categories"),
                arguments("批量设置", "POST", "/product/batch"),
                arguments("导入Excel", "POST", "/product/import"),
                arguments("导出Excel", "GET", "/product/export"),
                arguments("商品详情", "GET", "/product/detail/10"),
                arguments("商品更新", "PUT", "/product/update"),
                arguments("上下架", "POST", "/product/status"),
                arguments("删除商品", "DELETE", "/product/delete/10")
        );
    }

    private MockHttpServletRequestBuilder request(String method, String url) {
        switch (method) {
            case "GET":
                return get(url);
            case "POST":
                return post(url);
            case "PUT":
                return put(url);
            case "DELETE":
                return delete(url);
            default:
                throw new IllegalArgumentException("不支持的请求方法：" + method);
        }
    }

    private CurrentEmployeeBean loginBean(Integer shopId) {
        CurrentEmployeeBean bean = new CurrentEmployeeBean();
        bean.setEmployeeId(7);
        bean.setShopId(shopId);
        bean.setUsername("tom");
        return bean;
    }

    // ============ 1. 字段级校验：登录态字段被组件层从登录上下文读取 ============

    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedEndpoints")
    void 接口_带登录态_放行并从登录上下文读取(String name, String method, String url) throws Exception {
        when(requestHolder.get()).thenReturn(loginBean(3));

        // 带请求参数 shopId=99，验证业务只认登录上下文（与 Redis 注入的 shopId 一致），不看参数
        MvcResult result = mockMvc.perform(request(method, url).param("shopId", "99")).andReturn();

        assertNotEquals(401, result.getResponse().getStatus(), name + "：带登录态不应被拒");
        verify(requestHolder, atLeastOnce()).get();
    }

    // ============ 1. 字段级校验：登录态缺 shopId 拒绝（Fail 边界：查出全店数据） ============

    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedEndpoints")
    void 接口_登录态缺shopId_返回401(String name, String method, String url) throws Exception {
        when(requestHolder.get()).thenReturn(loginBean(null));

        MvcResult result = mockMvc.perform(request(method, url)).andReturn();

        assert401(result.getResponse(), name + "：登录态缺 shopId 必须拒绝");
    }

    // ============ 4. 非法输入：无登录态一律 401 ============

    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedEndpoints")
    void 接口_无登录态_返回401(String name, String method, String url) throws Exception {
        when(requestHolder.get()).thenReturn(null);

        MvcResult result = mockMvc.perform(request(method, url)).andReturn();

        assert401(result.getResponse(), name + "：无有效 token 必须返回 401");
    }

    // ============ 2. 类型与格式：401 响应契约（JSON + 中文消息） ============

    @Test
    void 未登录401_响应为JSON契约() throws Exception {
        when(requestHolder.get()).thenReturn(null);

        MvcResult result = mockMvc.perform(get("/product/list")).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assert401(response, "未登录应返回 401");
        assertTrue(response.getContentType() != null && response.getContentType().contains("application/json"),
                "401 响应应为 JSON，实际：" + response.getContentType());
        JSONUtil.parseObj(response.getContentAsString());
    }

    // ============ 3. 重复创建：同一登录态重复请求幂等放行 ============

    @Test
    void 同一登录态重复请求_幂等放行() throws Exception {
        when(requestHolder.get()).thenReturn(loginBean(3));

        MvcResult first = mockMvc.perform(get("/product/list")).andReturn();
        MvcResult second = mockMvc.perform(get("/product/list")).andReturn();

        assertNotEquals(401, first.getResponse().getStatus(), "首次请求应放行");
        assertNotEquals(401, second.getResponse().getStatus(), "重复请求应幂等放行");
        verify(requestHolder, atLeast(2)).get();
    }

    private void assert401(MockHttpServletResponse response, String message) throws Exception {
        assertTrue(response.getStatus() == 401,
                message + "，实际状态码：" + response.getStatus());
        assertTrue(response.getContentAsString().contains("未登录"),
                message + "，响应应含中文登录提示，实际：" + response.getContentAsString());
    }
}
