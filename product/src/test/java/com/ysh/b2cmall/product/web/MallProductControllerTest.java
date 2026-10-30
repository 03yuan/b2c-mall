package com.ysh.b2cmall.product.web;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ysh.b2cmall.product.dao.po.MallSkuQuery;
import com.ysh.b2cmall.product.dao.po.SkuPO;
import com.ysh.b2cmall.product.dao.po.SkuPageResult;
import com.ysh.b2cmall.product.service.CategoryService;
import com.ysh.b2cmall.product.service.SkuService;
import com.ysh.b2cmall.product.web.advice.GlobalExceptionHandler;
import com.ysh.b2cmall.product.web.controller.MallProductController;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * C 端商品卡片列表 GET /product/mall/products 的契约测试（TDD 红阶段）。
 * 对应 .tdd/task/mall-category-product-list.md 功能上下文 2.3（Controller/Service 层）：
 * - 2.3.1 新建 MallProductController（/product/mall，无 requestHolder 依赖）；
 *   categoryName/sortBy 可选、page/size 校验（非法 -> 400）
 * - 2.3.2 VO 组装：每张卡片含 skuId、thumbnail（images 首图）、name（全名）、price、displaySales
 * - 2.3.3 字段白名单：不返回 stock/storeStock、fakeSales、deliveryMethods、productType、type、
 *   status、marketPrice 等商家字段
 * - 2.3.4 下架商品恒不返回；无图商品 thumbnail=null 不伪造占位图
 *
 * 用例分类（表格驱动，适配 VO 组装与参数校验）：
 * 1. 字段级校验：卡片五要素齐全且取值正确
 * 2. 类型与格式：displaySales 合成（fake 为 null 按 0）、price 数值原样、thumbnail 首图/无图 null
 * 3. 重复创建：同参数重复请求响应完全一致（幂等）
 * 4. 非法输入：page/size 越界 -> 400；字段白名单不泄漏商家字段；下架不出现
 *
 * 测试为 standalone MockMvc + Mockito mock SkuService/CategoryService + GlobalExceptionHandler，
 * 不注册拦截器（公开免登录）。
 */
class MallProductControllerTest {

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

    /** 全字段上架样例行（images 双图，sales=10、fake=5、displaySales=15 由 DAO 计算列给出） */
    private SkuPO fullSkuPo() {
        SkuPO po = new SkuPO();
        po.setId(11);
        po.setShopId(3);
        po.setCategoryId(7);
        po.setName("测试商品");
        po.setPrice(new BigDecimal("99.00"));
        po.setMarketPrice(new BigDecimal("199.00"));
        po.setSales(10);
        po.setFakeSales(5);
        po.setDisplaySales(15);
        po.setStock(50);
        po.setStoreStock(8);
        po.setProductType(1);
        po.setType(2);
        po.setStatus(1);
        po.setImages("https://img.test/a.jpg,https://img.test/b.jpg");
        po.setDeliveryMethods("CITY,PICKUP");
        po.setSort(99);
        return po;
    }

    private JSONObject parseData(MockHttpServletResponse response) throws Exception {
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body != null && body.contains("\"data\""),
                "响应应为 BaseResponseVO JSON（含 data），实际：" + body);
        return JSONUtil.parseObj(body).getJSONObject("data");
    }

    // ============ 1. 字段级校验：卡片五要素齐全且取值正确 ============

    @Test
    void 卡片五要素齐全且取值正确_缩略图为images首图() throws Exception {
        SkuPO po = fullSkuPo();
        when(skuService.queryMallPage(any())).thenReturn(new SkuPageResult(Collections.singletonList(po), 1));

        MvcResult result = mockMvc.perform(get("/product/mall/products")).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(200, response.getStatus(), "查询成功应返回 200");
        JSONObject data = parseData(response);
        assertEquals(1L, data.getLong("total").longValue(), "total 应为命中总数");
        JSONArray list = data.getJSONArray("list");
        assertEquals(1, list.size(), "命中 1 行应返回 1 张卡片");
        JSONObject item = list.getJSONObject(0);

        assertEquals(11, item.getInt("skuId").intValue(), "skuId 取值错误");
        assertEquals("https://img.test/a.jpg", item.getStr("thumbnail"), "缩略图必须为 images 首图");
        assertEquals("测试商品", item.getStr("name"), "名称取值错误");
        assertEquals(0, item.getBigDecimal("price").compareTo(new BigDecimal("99.00")), "price 应原样返回");
        assertEquals(15, item.getInt("displaySales").intValue(), "前台销量 = sales + fake_sales = 15");
    }

    // ============ 2. 类型与格式：displaySales 来自 DAO 计算列（fake 为 null 时 DAO 兜底为 0） ============

    @Test
    void displaySales_来自DAO计算列_fake为null按0计() throws Exception {
        SkuPO po = fullSkuPo();
        po.setSales(0);
        po.setFakeSales(null);
        po.setDisplaySales(0);
        when(skuService.queryMallPage(any())).thenReturn(new SkuPageResult(Collections.singletonList(po), 1));

        JSONObject item = parseData(mockMvc.perform(get("/product/mall/products")).andReturn().getResponse())
                .getJSONArray("list").getJSONObject(0);

        assertEquals(0, item.getInt("displaySales").intValue(),
                "sales=0 且 fake_sales=null 时前台销量必须为 0（而非 null，由 DAO 计算列 COALESCE 兜底）");
    }

    // ============ 2. 类型与格式：无图 thumbnail=null 不伪造 ============

    @Test
    void 无图商品_thumbnail为null不伪造() throws Exception {
        SkuPO po = fullSkuPo();
        po.setImages(null);
        when(skuService.queryMallPage(any())).thenReturn(new SkuPageResult(Collections.singletonList(po), 1));

        JSONObject item = parseData(mockMvc.perform(get("/product/mall/products")).andReturn().getResponse())
                .getJSONArray("list").getJSONObject(0);

        assertNull(item.getStr("thumbnail"), "无图商品 thumbnail 必须为 null，不得伪造占位图");
    }

    // ============ 3. 字段白名单：不泄漏商家字段 ============

    @Test
    void 字段白名单_不返回库存注水等商家字段() throws Exception {
        SkuPO po = fullSkuPo();
        when(skuService.queryMallPage(any())).thenReturn(new SkuPageResult(Collections.singletonList(po), 1));

        JSONObject item = parseData(mockMvc.perform(get("/product/mall/products")).andReturn().getResponse())
                .getJSONArray("list").getJSONObject(0);

        // 只允许五要素
        assertEquals(new HashSet<>(Arrays.asList("skuId", "thumbnail", "name", "price", "displaySales")),
                item.keySet(), "卡片只允许五要素，不得泄漏任何商家字段");
    }

    // ============ 4. 非法输入：page/size 越界 -> 400 ============

    static Stream<Arguments> illegalPagingCases() {
        return Stream.of(
                arguments("page=0拒绝", "page", "0", "页码"),
                arguments("page=负数拒绝", "page", "-1", "页码"),
                arguments("size=0拒绝", "size", "0", "每页条数"),
                arguments("size=101拒绝", "size", "101", "每页条数"),
                arguments("page非数字拒绝", "page", "abc", "分页"),
                arguments("size非数字拒绝", "size", "abc", "分页")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("illegalPagingCases")
    void 非法分页参数_返回400(String caseName, String param, String value, String messageFragment) throws Exception {
        MvcResult result = mockMvc.perform(get("/product/mall/products").param(param, value)).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(400, response.getStatus(), caseName + "：非法分页参数必须拒绝为 400，实际：" + response.getStatus());
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains(messageFragment), caseName + "：400 响应应含中文原因「" + messageFragment + "」，实际：" + body);
    }

    // ============ 4. 非法输入：分页与 categoryName 参数透传 Service ============

    @Test
    void 合法参数_透传Service组装MallSkuQuery() throws Exception {
        when(skuService.queryMallPage(any())).thenReturn(new SkuPageResult(Collections.emptyList(), 0));

        mockMvc.perform(get("/product/mall/products")
                .param("categoryName", "水果")
                .param("page", "2")
                .param("size", "20")).andReturn();

        ArgumentCaptor<MallSkuQuery> captor = ArgumentCaptor.forClass(MallSkuQuery.class);
        verify(skuService, only()).queryMallPage(captor.capture());
        MallSkuQuery query = captor.getValue();
        assertEquals("水果", query.getCategoryName(), "categoryName 必须透传 Service");
        assertEquals(Integer.valueOf(2), query.getPage(), "page 必须透传 Service");
        assertEquals(Integer.valueOf(20), query.getSize(), "size 必须透传 Service");
    }

    // ============ 3. 重复创建：同参数重复请求响应完全一致 ============

    @Test
    void 同参数重复请求_响应完全一致不串态() throws Exception {
        SkuPO po = fullSkuPo();
        when(skuService.queryMallPage(any())).thenReturn(new SkuPageResult(Collections.singletonList(po), 1));

        JSONObject first = parseData(mockMvc.perform(get("/product/mall/products")).andReturn().getResponse());
        JSONObject second = parseData(mockMvc.perform(get("/product/mall/products")).andReturn().getResponse());

        assertEquals(first.toString(), second.toString(), "同参数重复请求必须返回完全一致的结果");
        verify(skuService, times(2)).queryMallPage(any());
    }

    // ============ 2.3.4 下架商品恒不返回（Service 层职责由 DAO 保证，这里断言空结果不伪造） ============

    @Test
    void 空结果_返回空列表与total为0_不伪造() throws Exception {
        when(skuService.queryMallPage(any())).thenReturn(new SkuPageResult(Collections.emptyList(), 0));

        JSONObject data = parseData(mockMvc.perform(get("/product/mall/products")).andReturn().getResponse());

        assertEquals(0L, data.getLong("total").longValue(), "空结果 total 必须为 0");
        assertEquals(0, data.getJSONArray("list").size(), "空结果必须为空列表（前端渲染「暂无数据」）");
        assertEquals(new HashSet<>(Arrays.asList("list", "total")), data.keySet(),
                "空结果时 data 只允许 list/total，不得伪造任何卡片");
    }
}
