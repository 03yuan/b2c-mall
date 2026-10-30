package com.ysh.b2cmall.product.web;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ysh.b2cmall.product.bean.CurrentEmployeeBean;
import com.ysh.b2cmall.product.component.RequestHolderComponent;
import com.ysh.b2cmall.product.dao.mapper.CategoryMapper;
import com.ysh.b2cmall.product.dao.po.CategoryPO;
import com.ysh.b2cmall.product.service.CategoryService;
import com.ysh.b2cmall.product.service.SkuService;
import com.ysh.b2cmall.product.service.impl.CategoryServiceImpl;
import com.ysh.b2cmall.product.web.advice.GlobalExceptionHandler;
import com.ysh.b2cmall.product.web.controller.ProductListController;
import com.ysh.b2cmall.product.web.interceptor.LoginInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 商品分类下拉 GET /product/categories 的契约测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-product-list.md 功能上下文 3.3（商品分类下拉数据）：
 * - 3.3.1 返回当前登录店铺、status=1 的类目列表（id+name，按 sort 排序）
 *   （Fail 边界：返回他店/禁用类目 -> 筛选出他店商品）
 *
 * 用例分类（表格驱动，适配下拉数据契约）：
 * 1. 字段级校验：每项只含 id+name 两列且取值正确、顺序按 sort
 * 2. 类型与格式：id 为整数、name 为字符串；空结果返回空数组（不伪造任何项）
 * 3. 重复创建：同请求重复调用 -> 结果完全一致（幂等、不串态）
 * 4. 非法输入：越权 shopId 参数被忽略，Service 只以登录 shopId 被调用
 *
 * 两段测试：
 * A. web -> Service 契约：standalone MockMvc + Mockito mock SkuService/CategoryService
 *    + 真实 LoginInterceptor + GlobalExceptionHandler
 * B. Service -> DAO 契约：真实 CategoryServiceImpl + Mockito mock CategoryMapper
 */
class ProductCategoryControllerTest {

    private RequestHolderComponent requestHolder;
    private SkuService skuService;
    private CategoryService categoryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        requestHolder = mock(RequestHolderComponent.class);
        skuService = mock(SkuService.class);
        categoryService = mock(CategoryService.class);
        when(requestHolder.get()).thenReturn(loginBean(3));
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ProductListController(skuService, requestHolder, categoryService))
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

    private CategoryPO category(int id, String name, int sort) {
        CategoryPO po = new CategoryPO();
        po.setId(id);
        po.setName(name);
        po.setParentId(0);
        po.setSort(sort);
        po.setStatus(1);
        return po;
    }

    /** 响应契约：BaseResponseVO JSON 且 data 为数组（占位返回 null 时此断言即失败） */
    private JSONArray parseDataArray(MockHttpServletResponse response) throws Exception {
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body != null && body.contains("\"data\""),
                "响应应为 BaseResponseVO JSON（含 data），实际：" + body);
        JSONArray data = JSONUtil.parseObj(body).getJSONArray("data");
        assertNotNull(data, "分类下拉的 data 必须为数组，实际：" + body);
        return data;
    }

    // ============ 1. 字段级校验：每项只含 id+name 且取值正确 ============

    @Test
    void 分类下拉_返回本店启用类目的id与name_按sort顺序() throws Exception {
        when(categoryService.listEnabledCategories(3))
                .thenReturn(Arrays.asList(category(2, "男装", 1), category(1, "女装", 2)));

        MvcResult result = mockMvc.perform(get("/product/categories")).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(200, response.getStatus(), "查询成功应返回 200");
        JSONArray data = parseDataArray(response);
        assertEquals(2, data.size(), "应返回 2 个启用类目");

        for (int i = 0; i < data.size(); i++) {
            JSONObject item = data.getJSONObject(i);
            assertEquals(new HashSet<>(Arrays.asList("id", "name")), item.keySet(),
                    "下拉项只允许 id/name，不得泄漏他店或内部字段");
        }
        JSONObject first = data.getJSONObject(0);
        assertEquals(2, first.getInt("id").intValue(), "id 应为整数且取值正确");
        assertEquals("男装", first.getStr("name"), "name 应为字符串且取值正确");
        JSONObject second = data.getJSONObject(1);
        assertEquals(1, second.getInt("id").intValue(), "第二项 id 取值正确");
        assertEquals("女装", second.getStr("name"), "第二项 name 取值正确");
    }

    // ============ 2. 类型与格式：空结果返回空数组，不伪造任何项 ============

    @Test
    void 空结果_返回空数组不伪造() throws Exception {
        when(categoryService.listEnabledCategories(3)).thenReturn(Collections.emptyList());

        MvcResult result = mockMvc.perform(get("/product/categories")).andReturn();

        JSONArray data = parseDataArray(result.getResponse());
        assertEquals(0, data.size(), "无可选类目时必须返回空数组，不得伪造默认项");
    }

    /**
     * 列：用例名、ShopId 请求参数值（越权/非法均须忽略）
     */
    static Stream<Arguments> ignoredShopIdCases() {
        return Stream.of(
                arguments("越权shopId=99被忽略", "99"),
                arguments("shopId非数字被忽略", "abc"),
                arguments("shopId为空被忽略", "")
        );
    }

    // ============ 4. 非法输入：越权 shopId 参数被忽略 ============

    @ParameterizedTest(name = "{0}")
    @MethodSource("ignoredShopIdCases")
    void 请求参数shopId被忽略_只以登录shopId调用Service(String caseName, String paramShopId) throws Exception {
        when(categoryService.listEnabledCategories(3)).thenReturn(Collections.emptyList());

        MvcResult result = mockMvc.perform(get("/product/categories").param("shopId", paramShopId)).andReturn();

        assertEquals(200, result.getResponse().getStatus(), caseName + "：参数被忽略不应报错");
        JSONArray data = parseDataArray(result.getResponse());
        assertEquals(0, data.size(), caseName + "：应返回登录店铺（3）的类目数组，不因越权参数串数据");
        verify(categoryService, only()).listEnabledCategories(3);
    }

    // ============ 3. 重复创建：同请求重复调用结果完全一致 ============

    @Test
    void 同请求重复调用_结果完全一致不串态() throws Exception {
        when(categoryService.listEnabledCategories(3))
                .thenReturn(Arrays.asList(category(2, "男装", 1), category(1, "女装", 2)));

        JSONArray first = parseDataArray(mockMvc.perform(get("/product/categories")).andReturn().getResponse());
        JSONArray second = parseDataArray(mockMvc.perform(get("/product/categories")).andReturn().getResponse());

        assertEquals(first.toString(), second.toString(), "同请求重复调用必须返回完全一致的结果");
        verify(categoryService, times(2)).listEnabledCategories(3);
    }

    // ============ B 段：Service -> DAO 契约（shopId 透传，结果原样返回） ============

    private CategoryServiceImpl serviceWithMapper(CategoryMapper mapper) {
        CategoryServiceImpl service = new CategoryServiceImpl();
        ReflectionTestUtils.setField(service, "categoryMapper", mapper);
        return service;
    }

    @Test
    void listEnabledCategories_shopId透传DAO且结果原样返回() {
        CategoryMapper mapper = mock(CategoryMapper.class);
        List<CategoryPO> expected = Arrays.asList(category(2, "男装", 1), category(1, "女装", 2));
        when(mapper.selectEnabledByShopId(3)).thenReturn(expected);

        List<CategoryPO> result = serviceWithMapper(mapper).listEnabledCategories(3);

        assertEquals(expected, result, "Service 必须原样返回 DAO 的启用类目结果");
        verify(mapper, only()).selectEnabledByShopId(3);
    }

    @Test
    void listEnabledCategories_空结果原样返回空集合不伪造() {
        CategoryMapper mapper = mock(CategoryMapper.class);
        when(mapper.selectEnabledByShopId(any())).thenReturn(Collections.emptyList());

        List<CategoryPO> result = serviceWithMapper(mapper).listEnabledCategories(3);

        assertNotNull(result, "空结果不得返回 null");
        assertEquals(0, result.size(), "空结果必须为空集合");
        assertTrue(result.stream().map(CategoryPO::getName).collect(Collectors.toList()).isEmpty(),
                "空结果不得伪造类目项");
    }
}
