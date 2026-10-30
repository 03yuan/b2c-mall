package com.ysh.b2cmall.product.web;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ysh.b2cmall.product.service.CategoryService;
import com.ysh.b2cmall.product.service.SkuService;
import com.ysh.b2cmall.product.web.advice.GlobalExceptionHandler;
import com.ysh.b2cmall.product.web.controller.MallProductController;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * C 端分类导航 GET /product/mall/categories 的契约测试（TDD 红阶段）。
 * 对应 .tdd/task/mall-category-product-list.md 功能上下文 1.2（Controller 层）：
 * - 1.2.1 返回去重后的分类名数组（List&lt;String&gt;，只含 name，Tab key 用名称）
 *   （Fail 边界：返回带 shop_id/id 的对象数组 -> 前端 Tab key 无法稳定）
 * - 1.2.2 空数据（无启用一级分类）返回空数组而非 null
 *   （Fail 边界：返回 null -> 前端 forEach 崩溃）
 * - 1.2.3 免登录可访问：无 Authorization 头 -> 200；方法内不引用 RequestHolderComponent
 *   （Fail 边界：被 TokenFilter 拦截 -> 401；引用 requestHolder.get() -> 无登录态 NPE）
 *
 * 用例分类（表格驱动，适配返回字符串数组的契约）：
 * 1. 字段级校验：data 为纯字符串数组、元素只含 name、顺序稳定
 * 2. 类型与格式：每个元素为字符串、非空；空结果返回空数组而非 null（不伪造）
 * 3. 重复创建：同请求重复调用结果完全一致（幂等、不串态）
 * 4. 非法输入：无 Authorization 头（免登录）仍 200；无入参接口，非法输入聚焦越权/缺失登录态
 *
 * 测试为 standalone MockMvc + Mockito mock CategoryService + GlobalExceptionHandler。
 * 不加载 TokenFilter/LoginInterceptor（本 Controller 公开免登录，无 requestHolder 依赖，
 * 故不注册拦截器——若 Controller 引用 requestHolder 会在测试中 NPE 暴露）。
 */
class MallProductCategoryControllerTest {

    private CategoryService categoryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        categoryService = mock(CategoryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new MallProductController(categoryService, mock(SkuService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** 响应契约：BaseResponseVO JSON 且 data 为数组（占位返回 null 时此断言即失败） */
    private JSONArray parseDataArray(MockHttpServletResponse response) throws Exception {
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body != null && body.contains("\"data\""),
                "响应应为 BaseResponseVO JSON（含 data），实际：" + body);
        JSONArray data = JSONUtil.parseObj(body).getJSONArray("data");
        assertNotNull(data, "分类导航的 data 必须为数组，实际：" + body);
        return data;
    }

    // ============ 1. 字段级校验：data 为纯字符串数组、只含 name、顺序稳定（表格驱动） ============

    /**
     * 列：用例名、Service 返回的分类名列表、期望的数组元素序列
     */
    static Stream<Arguments> categoryNameCases() {
        return Stream.of(
                arguments("去重后的分类名数组", Arrays.asList("数码", "水果", "蔬菜"),
                        Arrays.asList("数码", "水果", "蔬菜")),
                arguments("单个分类名", Collections.singletonList("水果"),
                        Collections.singletonList("水果"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("categoryNameCases")
    void categories_返回纯字符串分类名数组_顺序稳定(String caseName, List<String> serviceResult,
                                                List<String> expected) throws Exception {
        when(categoryService.listMallTopLevelNames()).thenReturn(serviceResult);

        MvcResult result = mockMvc.perform(get("/product/mall/categories")).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(200, response.getStatus(), caseName + "：查询成功应返回 200");
        JSONArray data = parseDataArray(response);
        assertEquals(expected.size(), data.size(), caseName + "：分类名数量不符");
        for (int i = 0; i < data.size(); i++) {
            Object element = data.get(i);
            assertTrue(element instanceof String, caseName + "：data 每项必须为字符串（name），不得是对象");
            assertEquals(expected.get(i), element, caseName + "：分类名或顺序不符");
        }
    }

    // ============ 2. 类型与格式：空结果返回空数组而非 null ============

    @Test
    void 空数据_返回空数组而非null() throws Exception {
        when(categoryService.listMallTopLevelNames()).thenReturn(Collections.emptyList());

        MvcResult result = mockMvc.perform(get("/product/mall/categories")).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(200, response.getStatus(), "空数据也应返回 200");
        JSONArray data = parseDataArray(response);
        assertEquals(0, data.size(), "无启用一级分类时必须返回空数组，不得返回 null（前端 forEach 崩溃）");
    }

    // ============ 3. 重复创建：同请求重复调用结果完全一致 ============

    @Test
    void 同请求重复调用_结果完全一致不串态() throws Exception {
        when(categoryService.listMallTopLevelNames()).thenReturn(Arrays.asList("数码", "水果", "蔬菜"));

        JSONArray first = parseDataArray(mockMvc.perform(get("/product/mall/categories")).andReturn().getResponse());
        JSONArray second = parseDataArray(mockMvc.perform(get("/product/mall/categories")).andReturn().getResponse());

        assertEquals(first.toString(), second.toString(), "同请求重复调用必须返回完全一致的结果（幂等、不串态）");
        verify(categoryService, times(2)).listMallTopLevelNames();
    }

    // ============ 4. 非法输入：免登录可访问（无 Authorization 头 -> 200） ============

    @Test
    void 无Authorization头_免登录可访问返回200() throws Exception {
        when(categoryService.listMallTopLevelNames()).thenReturn(Arrays.asList("水果"));

        MvcResult result = mockMvc.perform(get("/product/mall/categories")).andReturn();

        assertEquals(200, result.getResponse().getStatus(),
                "公开接口无 Authorization 头也应返回 200，不得被拦截为 401");
        verify(categoryService, only()).listMallTopLevelNames();
    }
}
