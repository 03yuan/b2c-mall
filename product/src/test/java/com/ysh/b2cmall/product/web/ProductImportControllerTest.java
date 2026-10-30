package com.ysh.b2cmall.product.web;

import cn.hutool.json.JSONUtil;
import com.ysh.b2cmall.product.bean.CurrentEmployeeBean;
import com.ysh.b2cmall.product.component.RequestHolderComponent;
import com.ysh.b2cmall.product.service.ImportResult;
import com.ysh.b2cmall.product.service.SkuService;
import com.ysh.b2cmall.product.web.advice.GlobalExceptionHandler;
import com.ysh.b2cmall.product.web.controller.ProductImportExportController;
import com.ysh.b2cmall.product.web.interceptor.LoginInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * 商品导入 POST /product/import 的契约测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-product-list.md 功能上下文 6.2（Controller 层，multipart）：
 * - 6.2.1 上传文件校验：非 .xlsx、空文件、超大小上限 -> 400
 *   （Fail 边界：500 或静默成功）
 * - 6.2.2 响应汇总：inserted 条数、updated 条数、errors（行号+原因）列表
 *   （Fail 边界：无汇总 -> 前端无法提示用户哪行失败）
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：响应 data 含 inserted/updated/errors 三段且取值正确
 * 2. 类型与格式：文件流传给 Service、shopId 取登录上下文
 * 3. 重复创建：同一文件重复上传响应一致（逐行幂等语义由 Service 保证）
 * 4. 非法输入：非 .xlsx / 空文件 / 超大小上限 -> 400 中文消息且不调用 Service；越权 shopId 忽略
 *
 * 测试结构：standalone MockMvc + Mockito mock SkuService + 真实 LoginInterceptor + GlobalExceptionHandler。
 */
class ProductImportControllerTest {

    private RequestHolderComponent requestHolder;
    private SkuService skuService;
    private MockMvc mockMvc;

    /** 上传大小上限 5MB（与任务文档 6.2.1「超大小上限」对应） */
    private static final int MAX_FILE_SIZE = 5 * 1024 * 1024;

    @BeforeEach
    void setUp() {
        requestHolder = mock(RequestHolderComponent.class);
        skuService = mock(SkuService.class);
        when(requestHolder.get()).thenReturn(loginBean(3));
        mockMvc = MockMvcBuilders.standaloneSetup(new ProductImportExportController(skuService, requestHolder))
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

    private MockMultipartFile xlsxFile(String content) {
        return new MockMultipartFile("file", "商品导入.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ============ 1. 字段级校验：响应 data 含 inserted/updated/errors 三段 ============

    @Test
    void 导入_响应含insertedUpdatedErrors三段且取值正确() throws Exception {
        when(skuService.importExcel(eq(3), any(InputStream.class)))
                .thenReturn(new ImportResult(1, 1, Collections.singletonList("第4行：商品ID 不存在")));

        MvcResult result = mockMvc.perform(multipart("/product/import").file(xlsxFile("fake-xlsx-bytes"))).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(200, response.getStatus(), "导入应返回 200");
        cn.hutool.json.JSONObject body = JSONUtil.parseObj(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(body.containsKey("data"), "响应应为 BaseResponseVO JSON（含 data）");
        cn.hutool.json.JSONObject data = body.getJSONObject("data");
        assertEquals(1, data.getInt("inserted"), "inserted 应透传 Service 结果");
        assertEquals(1, data.getInt("updated"), "updated 应透传 Service 结果");
        assertEquals(Collections.singletonList("第4行：商品ID 不存在"),
                data.getJSONArray("errors").toList(String.class), "errors 应含行号+原因");
    }

    // ============ 2. 类型与格式：文件流传给 Service、shopId 取登录上下文 ============

    @Test
    void 导入_文件流与登录shopId传给Service() throws Exception {
        when(skuService.importExcel(eq(3), any(InputStream.class)))
                .thenReturn(ImportResult.empty());

        MvcResult result = mockMvc.perform(multipart("/product/import").file(xlsxFile("fake-xlsx-bytes")))
                .andReturn();

        assertTrue(JSONUtil.parseObj(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).containsKey("data"),
                "响应应为 BaseResponseVO JSON（含 data）");
        verify(skuService).importExcel(eq(3), any(InputStream.class));
    }

    // ============ 3. 重复创建：同一文件重复上传响应一致 ============

    @Test
    void 同一文件重复上传_响应完全一致() throws Exception {
        when(skuService.importExcel(eq(3), any(InputStream.class)))
                .thenReturn(new ImportResult(0, 1, Collections.emptyList()));

        String first = mockMvc.perform(multipart("/product/import").file(xlsxFile("bytes")))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String second = mockMvc.perform(multipart("/product/import").file(xlsxFile("bytes")))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertEquals(first, second, "重复上传响应必须完全一致");
        assertTrue(second.contains("\"updated\":1"), "响应应含 updated 汇总");
    }

    // ============ 4. 非法输入：文件校验 -> 400 且不调用 Service（表格驱动） ============

    /**
     * 列：用例名、上传文件、期望错误消息片段
     */
    static Stream<Arguments> illegalFileCases() {
        return Stream.of(
                arguments("非xlsx文件",
                        new MockMultipartFile("file", "a.txt", "text/plain", new byte[]{1}),
                        ".xlsx"),
                arguments("空文件",
                        new MockMultipartFile("file", "a.xlsx", "application/octet-stream", new byte[0]),
                        "空"),
                arguments("超大小上限",
                        new MockMultipartFile("file", "a.xlsx", "application/octet-stream",
                                new byte[MAX_FILE_SIZE + 1]),
                        "大小")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("illegalFileCases")
    void 非法文件_返回400中文消息_不调用Service(String caseName, MockMultipartFile file, String messageFragment) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/product/import").file(file)).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(400, response.getStatus(), caseName + "：应返回 400");
        assertTrue(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains(messageFragment),
                caseName + "：错误消息应含「" + messageFragment + "」，实际：" + response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        verify(skuService, never()).importExcel(any(), any());
    }

    @Test
    void 越权shopId参数被忽略_导入只属于登录店铺() throws Exception {
        when(skuService.importExcel(eq(3), any(InputStream.class)))
                .thenReturn(ImportResult.empty());

        MvcResult result = mockMvc.perform(multipart("/product/import").file(xlsxFile("bytes")).param("shopId", "99"))
                .andReturn();

        assertTrue(JSONUtil.parseObj(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).containsKey("data"),
                "响应应为 BaseResponseVO JSON（含 data）");
        verify(skuService).importExcel(eq(3), any(InputStream.class));
        verify(skuService, never()).importExcel(eq(99), any());
    }
}
