package com.ysh.b2cmall.product.web;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ysh.b2cmall.product.bean.CurrentEmployeeBean;
import com.ysh.b2cmall.product.component.RequestHolderComponent;
import com.ysh.b2cmall.product.service.BatchUpdateResult;
import com.ysh.b2cmall.product.service.SkuService;
import com.ysh.b2cmall.product.web.advice.GlobalExceptionHandler;
import com.ysh.b2cmall.product.web.controller.ProductBatchController;
import com.ysh.b2cmall.product.web.interceptor.LoginInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 商品批量设置 POST /product/batch 的契约测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-product-list.md 功能上下文 4.2（Controller 层）：
 * - 4.2.1 请求体（skuIds + action + value）校验后调 Service，响应含生效条数与跳过条数
 *   （Fail 边界：无反馈条数前端无法提示部分失败）
 *
 * 用例分类（表格驱动，适配批量设置契约）：
 * 1. 字段级校验：响应 data 含 affected/skipped 且取值正确；请求体三字段原样透传 Service
 * 2. 类型与格式：请求体 JSON 绑定（skuIds 整数列表、action/value 字符串）；响应为 BaseResponseVO JSON
 * 3. 重复创建：同请求重复调用结果完全一致（幂等、不串态）
 * 4. 非法输入：缺/空 skuIds、缺 action → 400 中文消息；越权 shopId 参数被忽略；
 *    Service 抛业务异常 → 400 中文消息
 *
 * 测试结构：standalone MockMvc + Mockito mock SkuService + 真实 LoginInterceptor + GlobalExceptionHandler。
 */
class ProductBatchControllerTest {

    private RequestHolderComponent requestHolder;
    private SkuService skuService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        requestHolder = mock(RequestHolderComponent.class);
        skuService = mock(SkuService.class);
        when(requestHolder.get()).thenReturn(loginBean(3));
        mockMvc = MockMvcBuilders.standaloneSetup(new ProductBatchController(skuService, requestHolder))
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

    private String requestBody(String skuIdsJson, String actionJson, String valueJson) {
        return "{\"skuIds\":" + skuIdsJson + ",\"action\":" + actionJson + ",\"value\":" + valueJson + "}";
    }

    private MvcResult perform(String body) throws Exception {
        return mockMvc.perform(post("/product/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    /** 响应契约：BaseResponseVO JSON 且 data 含 affected/skipped（占位返回 null 时此断言即失败） */
    private JSONObject parseData(MockHttpServletResponse response) throws Exception {
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body != null && body.contains("\"data\""),
                "响应应为 BaseResponseVO JSON（含 data），实际：" + body);
        JSONObject data = JSONUtil.parseObj(body).getJSONObject("data");
        assertTrue(data != null && data.containsKey("affected") && data.containsKey("skipped"),
                "data 必须含 affected/skipped，实际：" + body);
        return data;
    }

    // ============ 1. 字段级校验：响应含 affected/skipped 且取值正确 ============

    @Test
    void 批量设置_响应含生效与跳过条数() throws Exception {
        when(skuService.batchUpdate(eq(3), anyList(), anyString(), anyString()))
                .thenReturn(new BatchUpdateResult(2, 1));

        MvcResult result = perform(requestBody("[1,2,99]", "\"status\"", "\"0\""));

        assertEquals(200, result.getResponse().getStatus(), "批量设置成功应返回 200");
        JSONObject data = parseData(result.getResponse());
        assertEquals(2, data.getInt("affected").intValue(), "affected 应为生效条数 2");
        assertEquals(1, data.getInt("skipped").intValue(), "skipped 应为跳过条数 1");
    }

    // ============ 2. 类型与格式：请求体三字段原样透传 Service（表格驱动） ============

    /**
     * 列：用例名、请求体 skuIds JSON、action JSON、value JSON、期望 skuIds、期望 action、期望 value
     */
    static Stream<Arguments> passThroughCases() {
        return Stream.of(
                arguments("批量下架", "[1,2,3]", "\"status\"", "\"0\"",
                        Arrays.asList(1, 2, 3), "status", "0"),
                arguments("批量上架", "[1]", "\"status\"", "\"1\"",
                        Arrays.asList(1), "status", "1"),
                arguments("配送方式多选整组替换", "[2,3]", "\"deliveryMethods\"", "\"CITY,PICKUP\"",
                        Arrays.asList(2, 3), "deliveryMethods", "CITY,PICKUP"),
                arguments("分类切换", "[1,2]", "\"categoryId\"", "\"101\"",
                        Arrays.asList(1, 2), "categoryId", "101"),
                arguments("排序统一设为9", "[3]", "\"sort\"", "\"9\"",
                        Arrays.asList(3), "sort", "9"),
                arguments("库存设为固定值", "[1,2,3]", "\"stock\"", "\"5\"",
                        Arrays.asList(1, 2, 3), "stock", "5")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("passThroughCases")
    void 请求体三字段原样透传Service(String caseName, String skuIdsJson, String actionJson, String valueJson,
                                    List<Integer> expectedIds, String expectedAction, String expectedValue) throws Exception {
        when(skuService.batchUpdate(eq(3), anyList(), anyString(), anyString()))
                .thenReturn(new BatchUpdateResult(expectedIds.size(), 0));

        MvcResult result = perform(requestBody(skuIdsJson, actionJson, valueJson));

        assertEquals(200, result.getResponse().getStatus(), caseName + "：请求应成功");
        JSONObject data = parseData(result.getResponse());
        assertEquals(expectedIds.size(), data.getInt("affected").intValue(), caseName + "：affected 应为生效条数");
        assertEquals(0, data.getInt("skipped").intValue(), caseName + "：skipped 应为 0");
        verify(skuService).batchUpdate(eq(3), eq(expectedIds), eq(expectedAction), eq(expectedValue));
    }

    // ============ 3. 重复创建：同请求重复调用结果完全一致 ============

    @Test
    void 同请求重复调用_结果完全一致() throws Exception {
        when(skuService.batchUpdate(eq(3), anyList(), anyString(), anyString()))
                .thenReturn(new BatchUpdateResult(3, 0));
        String body = requestBody("[1,2,3]", "\"status\"", "\"0\"");

        JSONObject first = parseData(perform(body).getResponse());
        JSONObject second = parseData(perform(body).getResponse());

        assertEquals(first.toString(), second.toString(), "同请求重复调用必须返回完全一致的结果");
        assertEquals(3, first.getInt("affected").intValue(), "affected 应为 3");
        assertEquals(0, first.getInt("skipped").intValue(), "skipped 应为 0");
        verify(skuService, times(2)).batchUpdate(eq(3), eq(Arrays.asList(1, 2, 3)), eq("status"), eq("0"));
    }

    // ============ 4. 非法输入：请求体字段缺失/为空 → 400 中文消息（表格驱动） ============

    /**
     * 列：用例名、请求体 JSON、期望中文消息片段
     */
    static Stream<Arguments> illegalBodyCases() {
        return Stream.of(
                arguments("缺skuIds字段", "{\"action\":\"status\",\"value\":\"0\"}", "商品"),
                arguments("skuIds为空数组", "{\"skuIds\":[],\"action\":\"status\",\"value\":\"0\"}", "商品"),
                arguments("缺action字段", "{\"skuIds\":[1,2]}", "操作类型"),
                arguments("action为空白", "{\"skuIds\":[1,2],\"action\":\" \",\"value\":\"0\"}", "操作类型")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("illegalBodyCases")
    void 非法请求体_返回400且中文消息(String caseName, String body, String messageFragment) throws Exception {
        MvcResult result = perform(body);
        MockHttpServletResponse response = result.getResponse();

        assertEquals(400, response.getStatus(), caseName + "：非法请求体必须返回 400");
        String responseBody = response.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(responseBody.contains(messageFragment),
                caseName + "：响应应含「" + messageFragment + "」，实际：" + responseBody);
    }

    @Test
    void 越权shopId参数被忽略_只以登录shopId调用Service() throws Exception {
        when(skuService.batchUpdate(eq(3), anyList(), anyString(), anyString()))
                .thenReturn(new BatchUpdateResult(1, 0));

        MvcResult result = mockMvc.perform(post("/product/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("[1]", "\"status\"", "\"0\""))
                        .param("shopId", "99"))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus(), "越权参数被忽略不应报错");
        JSONObject data = parseData(result.getResponse());
        assertEquals(1, data.getInt("affected").intValue(), "应返回登录店铺的生效条数");
        verify(skuService).batchUpdate(eq(3), eq(Arrays.asList(1)), eq("status"), eq("0"));
    }

    @Test
    void Service业务校验拒绝_返回400且中文消息() throws Exception {
        when(skuService.batchUpdate(any(), anyList(), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("库存必须为大于等于 0 的整数"));

        MvcResult result = perform(requestBody("[1]", "\"stock\"", "\"-1\""));
        MockHttpServletResponse response = result.getResponse();

        assertEquals(400, response.getStatus(), "业务校验拒绝必须返回 400");
        String responseBody = response.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(responseBody.contains("库存"), "响应应含中文原因，实际：" + responseBody);
    }
}
