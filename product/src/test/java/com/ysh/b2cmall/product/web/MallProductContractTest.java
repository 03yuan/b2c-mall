package com.ysh.b2cmall.product.web;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ysh.b2cmall.product.dao.po.MallSkuQuery;
import com.ysh.b2cmall.product.dao.po.SkuPageResult;
import com.ysh.b2cmall.product.service.CategoryService;
import com.ysh.b2cmall.product.service.SkuService;
import com.ysh.b2cmall.product.web.advice.GlobalExceptionHandler;
import com.ysh.b2cmall.product.web.controller.MallProductController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * C 端商城接口契约测试（TDD 契约固化）：默认「全部」与前后端边界。
 * 对应 .tdd/task/mall-category-product-list.md 功能上下文 4.2（接口契约）：
 * - 4.2.1 categories 返回的数据不含「全部」占位项（前端自行渲染默认高亮「全部」Tab）；
 *   products 不传 categoryName 即「全部」——前端据此渲染默认高亮「全部」Tab。
 *
 * 说明：底层实现（1.2 categories 直接透传 mapper 结果、2.3 products 无参时 categoryName=null）
 * 已存在；本测试在 Controller 层锁定「无占位 + 无参=全部」两个此前未显式断言的前后端边界契约，
 * 作为回归护栏（防止后续有人往 categories 塞「全部」占位、或给 products 无参悄悄加默认分类）。
 *
 * 用例分类（表格驱动语义）：
 * 1. 字段级校验：categories 返回纯分类名列表，不含「全部」占位项
 * 2. 类型与格式：products 无参 -> Service 收到 categoryName=null（「全部」语义，非空串）
 * 3. 重复创建：categories/products 同请求重复响应一致
 * 4. 非法输入：products 传 categoryName 与无参互斥（传了则 categoryName 非 null，不串成「全部」）
 *
 * standalone MockMvc + mock CategoryService/SkuService + GlobalExceptionHandler，无拦截器。
 */
class MallProductContractTest {

    private CategoryService categoryService;
    private SkuService skuService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        categoryService = mock(CategoryService.class);
        skuService = mock(SkuService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new MallProductController(categoryService, skuService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ============ 1. 字段级校验：categories 不含「全部」占位项（4.2.1） ============

    @Test
    void categories接口_不含全部占位项() throws Exception {
        when(categoryService.listMallTopLevelNames()).thenReturn(Arrays.asList("数码", "水果", "蔬菜"));

        MockHttpServletResponse response = mockMvc.perform(get("/product/mall/categories"))
                .andReturn().getResponse();

        assertEquals(200, response.getStatus(), "分类导航应返回 200");
        JSONArray data = JSONUtil.parseObj(response.getContentAsString(StandardCharsets.UTF_8))
                .getJSONArray("data");
        assertEquals(3, data.size(), "分类数量不符：后端不得额外塞占位项");

        for (int i = 0; i < data.size(); i++) {
            assertTrue(!("全部".equals(data.getStr(i))),
                    "categories 不得返回「全部」占位项（默认高亮「全部」Tab 为前端职责），第 " + i + " 项非法：" + data.getStr(i));
        }
    }

    @Test
    void categories接口_空数据也不含全部占位项() throws Exception {
        when(categoryService.listMallTopLevelNames()).thenReturn(Collections.emptyList());

        MockHttpServletResponse response = mockMvc.perform(get("/product/mall/categories"))
                .andReturn().getResponse();

        JSONArray data = JSONUtil.parseObj(response.getContentAsString(StandardCharsets.UTF_8))
                .getJSONArray("data");
        assertEquals(0, data.size(), "空数据时必须返回空数组，不得塞「全部」占位项");
    }

    // ============ 2. 类型与格式：products 无参 -> categoryName=null（「全部」语义） ============

    @Test
    void products无参_Service收到categoryName为null() throws Exception {
        when(skuService.queryMallPage(any())).thenReturn(new SkuPageResult(Collections.emptyList(), 0));

        mockMvc.perform(get("/product/mall/products")).andReturn();

        ArgumentCaptor<MallSkuQuery> captor = ArgumentCaptor.forClass(MallSkuQuery.class);
        verify(skuService, only()).queryMallPage(captor.capture());
        assertNull(captor.getValue().getCategoryName(),
                "products 不传 categoryName 时必须为 null（「全部」语义），不得悄悄塞默认分类名");
    }

    // ============ 4. 非法输入：products 传 categoryName 与无参互斥 ============

    @Test
    void products传分类_Service收到该分类_与无参互斥() throws Exception {
        when(skuService.queryMallPage(any())).thenReturn(new SkuPageResult(Collections.emptyList(), 0));

        mockMvc.perform(get("/product/mall/products").param("categoryName", "水果")).andReturn();

        ArgumentCaptor<MallSkuQuery> captor = ArgumentCaptor.forClass(MallSkuQuery.class);
        verify(skuService, only()).queryMallPage(captor.capture());
        assertEquals("水果", captor.getValue().getCategoryName(),
                "products 传 categoryName=水果 时必须透传该分类，与无参「全部」语义互斥");
    }

    // ============ 3. 重复创建：同请求重复响应一致 ============

    @Test
    void categories同请求重复_响应一致() throws Exception {
        when(categoryService.listMallTopLevelNames()).thenReturn(Arrays.asList("水果", "蔬菜"));

        JSONObject first = JSONUtil.parseObj(
                mockMvc.perform(get("/product/mall/categories")).andReturn().getResponse()
                        .getContentAsString(StandardCharsets.UTF_8));
        JSONObject second = JSONUtil.parseObj(
                mockMvc.perform(get("/product/mall/categories")).andReturn().getResponse()
                        .getContentAsString(StandardCharsets.UTF_8));

        assertEquals(first.toString(), second.toString(), "categories 同请求重复必须返回一致结果");
    }

    @Test
    void products无参同请求重复_响应一致() throws Exception {
        when(skuService.queryMallPage(any())).thenReturn(new SkuPageResult(Collections.emptyList(), 0));

        JSONObject first = JSONUtil.parseObj(
                mockMvc.perform(get("/product/mall/products")).andReturn().getResponse()
                        .getContentAsString(StandardCharsets.UTF_8));
        JSONObject second = JSONUtil.parseObj(
                mockMvc.perform(get("/product/mall/products")).andReturn().getResponse()
                        .getContentAsString(StandardCharsets.UTF_8));

        assertEquals(first.toString(), second.toString(), "products 无参同请求重复必须返回一致结果");
    }
}
