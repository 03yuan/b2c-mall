package com.ysh.b2cmall.product.web;

import com.alibaba.excel.EasyExcel;
import com.ysh.b2cmall.product.bean.CurrentEmployeeBean;
import com.ysh.b2cmall.product.component.RequestHolderComponent;
import com.ysh.b2cmall.product.dao.po.SkuPO;
import com.ysh.b2cmall.product.dao.po.SkuQuery;
import com.ysh.b2cmall.product.service.SkuService;
import com.ysh.b2cmall.product.web.advice.GlobalExceptionHandler;
import com.ysh.b2cmall.product.web.controller.ProductImportExportController;
import com.ysh.b2cmall.product.web.excel.ProductExcelColumns;
import com.ysh.b2cmall.product.web.interceptor.LoginInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 商品列表导出 GET /product/export 的契约测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-product-list.md 功能上下文 5.2（web 层 - Excel 下载）：
 * - 5.2.1 返回 .xlsx 下载流，表头列=列表列+商品ID（即导入模板，单一列定义来源），
 *   文件名含时间戳，响应头 Content-Disposition 正确
 *   （Fail 边界：导出表头与导入模板各自硬编码 -> 漂移后导出文件无法导入）
 *
 * 用例分类（表格驱动，适配导出契约）：
 * 1. 字段级校验：表头逐列与 ProductExcelColumns.HEADERS 一致；数据行与 PO 字段映射一致
 * 2. 类型与格式：Content-Type 为 xlsx、Content-Disposition attachment 且文件名含时间戳；
 *    筛选参数原样透传导出查询
 * 3. 重复创建：同条件重复导出数据行完全一致
 * 4. 非法输入：越权 shopId 参数被忽略（导出查询只带登录 shopId）；空结果导出合法 xlsx 仅表头
 *
 * 测试结构：standalone MockMvc + Mockito mock SkuService + 真实 LoginInterceptor + GlobalExceptionHandler，
 * 响应字节用 EasyExcel 读回断言。
 */
class ProductExportControllerTest {

    private RequestHolderComponent requestHolder;
    private SkuService skuService;
    private MockMvc mockMvc;

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

    private SkuPO sku(int id, String name, int sort) {
        SkuPO po = new SkuPO();
        po.setId(id);
        po.setShopId(3);
        po.setCategoryId(100);
        po.setName(name);
        po.setPrice(new BigDecimal("10.00"));
        po.setMarketPrice(new BigDecimal("20.00"));
        po.setStock(7);
        po.setSales(11);
        po.setFakeSales(5);
        po.setStoreStock(3);
        po.setType(1);
        po.setProductType(1);
        po.setImages("a.jpg,b.jpg");
        po.setDeliveryMethods("EXPRESS");
        po.setSort(sort);
        po.setStatus(1);
        return po;
    }

    /** 读回 Excel 全部行（headRowNumber=0，首行表头也作为一行 Map<列索引, 文本>） */
    private List<Map<Integer, String>> readAllRows(byte[] bytes) {
        return EasyExcel.read(new ByteArrayInputStream(bytes)).headRowNumber(0).sheet(0).doReadSync();
    }

    private List<String> rowValues(Map<Integer, String> row) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < ProductExcelColumns.HEADERS.size(); i++) {
            values.add(row.get(i));
        }
        return values;
    }

    // ============ 1. 字段级校验：表头逐列一致 + 数据行与 PO 字段映射一致 ============

    @Test
    void 导出_表头与单一列定义逐列一致_数据行与PO字段一致() throws Exception {
        when(skuService.exportList(any())).thenReturn(Arrays.asList(sku(10, "商品A", 9), sku(11, "商品B", 8)));

        MvcResult result = mockMvc.perform(get("/product/export")).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(200, response.getStatus(), "导出应返回 200");
        List<Map<Integer, String>> rows = readAllRows(response.getContentAsByteArray());
        assertEquals(3, rows.size(), "应为 1 行表头 + 2 行数据");

        List<String> header = rowValues(rows.get(0));
        assertEquals(ProductExcelColumns.HEADERS, header, "表头必须与单一列定义逐列一致");

        List<String> firstDataRow = rowValues(rows.get(1));
        List<String> expected = Arrays.asList("10", "商品A", "20.00", "10.00", "11", "5",
                "7", "3", "1", "1", "1", "EXPRESS", "9");
        assertEquals(expected, firstDataRow, "数据行各列必须与 PO 字段映射一致（商品ID 在首列）");
    }

    // ============ 2. 类型与格式：xlsx 响应头契约 ============

    @Test
    void 导出_响应头为xlsx下载流_文件名含时间戳() throws Exception {
        when(skuService.exportList(any())).thenReturn(Collections.emptyList());

        MvcResult result = mockMvc.perform(get("/product/export")).andReturn();
        MockHttpServletResponse response = result.getResponse();

        String contentType = response.getContentType();
        assertTrue(contentType != null
                        && contentType.contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                "Content-Type 必须为 xlsx，实际：" + contentType);

        String disposition = response.getHeader("Content-Disposition");
        assertTrue(disposition != null && disposition.contains("attachment"),
                "Content-Disposition 必须为 attachment，实际：" + disposition);
        String fileName = disposition.replaceFirst("(?i).*filename\\*?=\"?(?:UTF-8'')?", "")
                .replaceAll("[\";].*$", "");
        assertTrue(fileName.matches(".*\\d{8}.*\\.xlsx"),
                "文件名必须含时间戳且为 .xlsx，实际：" + disposition);
    }

    // ============ 2. 类型与格式：筛选参数原样透传导出查询（表格驱动） ============

    /**
     * 列：用例名、请求参数配置、对导出查询的断言
     */
    static Stream<Arguments> filterPassThroughCases() {
        return Stream.of(
                arguments("名称模糊透传",
                        (Consumer<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder>) b -> b.param("name", "咖啡"),
                        (Consumer<SkuQuery>) q -> assertEquals("咖啡", q.getName(), "name 应透传")),
                arguments("商品类型透传",
                        (Consumer<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder>) b -> b.param("productType", "2"),
                        (Consumer<SkuQuery>) q -> assertEquals(2, q.getProductType().intValue(), "productType 应透传")),
                arguments("商品类别透传",
                        (Consumer<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder>) b -> b.param("type", "3"),
                        (Consumer<SkuQuery>) q -> assertEquals(3, q.getType().intValue(), "type 应透传")),
                arguments("状态透传",
                        (Consumer<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder>) b -> b.param("status", "0"),
                        (Consumer<SkuQuery>) q -> assertEquals(0, q.getStatus().intValue(), "status 应透传")),
                arguments("分类透传",
                        (Consumer<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder>) b -> b.param("categoryId", "101"),
                        (Consumer<SkuQuery>) q -> assertEquals(101, q.getCategoryId().intValue(), "categoryId 应透传")),
                arguments("配送方式多选透传",
                        (Consumer<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder>) b ->
                                b.param("deliveryMethods", "CITY").param("deliveryMethods", "PICKUP"),
                        (Consumer<SkuQuery>) q -> assertEquals(Arrays.asList("CITY", "PICKUP"),
                                q.getDeliveryMethods(), "deliveryMethods 应透传"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("filterPassThroughCases")
    void 筛选参数_原样透传导出查询(String caseName,
                                 Consumer<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> params,
                                 Consumer<SkuQuery> queryAssert) throws Exception {
        when(skuService.exportList(any())).thenReturn(Collections.emptyList());

        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder = get("/product/export");
        params.accept(builder);
        mockMvc.perform(builder);

        ArgumentCaptor<SkuQuery> captor = ArgumentCaptor.forClass(SkuQuery.class);
        verify(skuService).exportList(captor.capture());
        SkuQuery query = captor.getValue();
        assertEquals(3, query.getShopId().intValue(), caseName + "：shopId 必须取登录上下文");
        queryAssert.accept(query);
    }

    // ============ 3. 重复创建：同条件重复导出数据行完全一致 ============

    @Test
    void 同条件重复导出_数据行完全一致() throws Exception {
        when(skuService.exportList(any())).thenReturn(Arrays.asList(sku(10, "商品A", 9)));

        byte[] first = mockMvc.perform(get("/product/export")).andReturn().getResponse().getContentAsByteArray();
        byte[] second = mockMvc.perform(get("/product/export")).andReturn().getResponse().getContentAsByteArray();

        List<Map<Integer, String>> firstRows = readAllRows(first);
        List<Map<Integer, String>> secondRows = readAllRows(second);
        assertEquals(firstRows.stream().map(this::rowValues).collect(Collectors.toList()),
                secondRows.stream().map(this::rowValues).collect(Collectors.toList()),
                "重复导出的表头与数据行必须完全一致");
    }

    // ============ 4. 非法输入：越权 shopId 被忽略；空结果导出合法 xlsx 仅表头 ============

    @Test
    void 越权shopId参数被忽略_导出查询只带登录shopId() throws Exception {
        when(skuService.exportList(any())).thenReturn(Collections.emptyList());

        MvcResult result = mockMvc.perform(get("/product/export").param("shopId", "99")).andReturn();

        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertTrue(bytes.length > 0, "越权参数被忽略后仍应导出登录店铺的合法 xlsx 文件");
        ArgumentCaptor<SkuQuery> captor = ArgumentCaptor.forClass(SkuQuery.class);
        verify(skuService).exportList(captor.capture());
        assertEquals(3, captor.getValue().getShopId().intValue(), "越权 shopId 必须被忽略");
    }

    @Test
    void 空结果_导出合法xlsx仅表头不伪造数据行() throws Exception {
        when(skuService.exportList(any())).thenReturn(Collections.emptyList());

        MvcResult result = mockMvc.perform(get("/product/export")).andReturn();
        byte[] bytes = result.getResponse().getContentAsByteArray();

        assertTrue(bytes.length > 0, "空结果也必须导出合法的 xlsx 文件");
        List<Map<Integer, String>> rows = readAllRows(bytes);
        assertEquals(1, rows.size(), "空结果应只有 1 行表头，不得伪造数据行");
        assertEquals(ProductExcelColumns.HEADERS, rowValues(rows.get(0)), "表头必须与单一列定义一致");
    }
}
