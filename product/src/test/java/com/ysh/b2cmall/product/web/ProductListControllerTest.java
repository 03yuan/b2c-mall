package com.ysh.b2cmall.product.web;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ysh.b2cmall.product.bean.CurrentEmployeeBean;
import com.ysh.b2cmall.product.component.RequestHolderComponent;
import com.ysh.b2cmall.product.dao.mapper.SkuMapper;
import com.ysh.b2cmall.product.dao.po.SkuPO;
import com.ysh.b2cmall.product.dao.po.SkuPageResult;
import com.ysh.b2cmall.product.dao.po.SkuQuery;
import com.ysh.b2cmall.product.service.CategoryService;
import com.ysh.b2cmall.product.service.SkuService;
import com.ysh.b2cmall.product.service.impl.SkuServiceImpl;
import com.ysh.b2cmall.product.web.advice.GlobalExceptionHandler;
import com.ysh.b2cmall.product.web.controller.ProductListController;
import com.ysh.b2cmall.product.web.interceptor.LoginInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
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
 * 商品列表 GET /product/list 的参数校验与 VO 组装测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-product-list.md 功能上下文 3.2（Service/Controller 层）：
 * - 3.2.1 Controller 参数校验（非法 page/size -> 400）+ VO 组装：每行 14 列齐全，
 *   缩略图 = images 第一张图（非整串）
 *   （Fail 边界：漏列前端表头对不上；images 整串返回）
 * - 3.2.2 空结果返回空列表 + total=0（前端渲染「暂无数据」），任何字段不伪造数值
 *   （Fail 边界：填充 0/示例数据——用户明确反感虚假数据）
 *
 * 用例分类（表格驱动，适配 VO 组装与参数校验）：
 * 1. 字段级校验：行 VO 14 列齐全且取值正确；缩略图=images 首图
 * 2. 类型与格式：deliveryMethods 多选列表绑定、BigDecimal 价格原样、空 images 不伪造、
 *    非数字分页参数
 * 3. 重复创建：同参数重复请求 -> 响应完全一致（幂等、不串态）
 * 4. 非法输入：page/size 越界或非法 -> 400 中文消息；空结果 -> 空列表 + total=0 且不伪造
 *    任何字段；越权 shopId 参数被忽略（shopId 恒取登录上下文）且筛选照常透传
 *
 * 两段测试：
 * A. web -> Service 契约：standalone MockMvc + Mockito mock SkuService + 真实
 *    LoginInterceptor + GlobalExceptionHandler（VO 组装/参数校验快速穷举边界）
 * B. Service -> DAO 契约：真实 SkuServiceImpl + Mockito mock SkuMapper
 *    （queryPage 组装 rows+total、空结果不伪造、筛选与 shopId 原样透传 DAO）
 */
class ProductListControllerTest {

    /** 行 VO 必须齐全的 14 列（漏列前端表头对不上） */
    private static final String[] ITEM_COLUMNS = {
            "skuId", "thumbnail", "name", "marketPrice", "price", "sales", "fakeSales",
            "stock", "storeStock", "productType", "type", "status", "deliveryMethods", "sort"};

    private RequestHolderComponent requestHolder;
    private SkuService skuService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        requestHolder = mock(RequestHolderComponent.class);
        skuService = mock(SkuService.class);
        when(requestHolder.get()).thenReturn(loginBean(3));
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ProductListController(skuService, requestHolder, mock(CategoryService.class)))
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

    /** 全字段样例行（images 双图，用于断言缩略图非整串） */
    private SkuPO fullSkuPo() {
        SkuPO po = new SkuPO();
        po.setId(11);
        po.setShopId(3);
        po.setCategoryId(7);
        po.setName("测试商品");
        po.setMarketPrice(new BigDecimal("199.00"));
        po.setPrice(new BigDecimal("99.00"));
        po.setSales(321);
        po.setFakeSales(1000);
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

    /** 响应契约：BaseResponseVO JSON 且含 data（占位返回 null 时此断言即失败） */
    private JSONObject parseData(MockHttpServletResponse response) throws Exception {
        String body = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body != null && body.contains("\"data\""),
                "响应应为 BaseResponseVO JSON（含 data），实际：" + body);
        return JSONUtil.parseObj(body).getJSONObject("data");
    }

    // ============ 1. 字段级校验：行 VO 14 列齐全且取值正确 ============

    @Test
    void 列表VO_14列齐全且取值正确_缩略图为images首图() throws Exception {
        SkuPO po = fullSkuPo();
        when(skuService.queryPage(any())).thenReturn(new SkuPageResult(Collections.singletonList(po), 1));

        MvcResult result = mockMvc.perform(get("/product/list")).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(200, response.getStatus(), "查询成功应返回 200");
        JSONObject data = parseData(response);
        assertEquals(1L, data.getLong("total").longValue(), "total 应为条件命中总数");
        JSONArray list = data.getJSONArray("list");
        assertEquals(1, list.size(), "命中 1 行时应返回 1 行");
        JSONObject item = list.getJSONObject(0);

        for (String key : ITEM_COLUMNS) {
            assertTrue(item.containsKey(key), "行 VO 缺列：" + key);
        }
        assertEquals(11, item.getInt("skuId").intValue(), "skuId 取值错误");
        assertEquals("https://img.test/a.jpg", item.getStr("thumbnail"),
                "缩略图必须为 images 第一张图，不得返回整串");
        assertEquals("测试商品", item.getStr("name"), "商品名称取值错误");
        assertEquals(0, item.getBigDecimal("marketPrice").compareTo(new BigDecimal("199.00")),
                "市场价应原样返回（BigDecimal）");
        assertEquals(0, item.getBigDecimal("price").compareTo(new BigDecimal("99.00")),
                "销售价应原样返回（BigDecimal）");
        assertEquals(321, item.getInt("sales").intValue(), "商品销量取值错误");
        assertEquals(1000, item.getInt("fakeSales").intValue(), "注水销量取值错误");
        assertEquals(50, item.getInt("stock").intValue(), "仓库库存取值错误");
        assertEquals(8, item.getInt("storeStock").intValue(), "门店库存取值错误");
        assertEquals(1, item.getInt("productType").intValue(), "商品类型取值错误");
        assertEquals(2, item.getInt("type").intValue(), "商品类别取值错误");
        assertEquals(1, item.getInt("status").intValue(), "状态取值错误");
        assertEquals("CITY,PICKUP", item.getStr("deliveryMethods"), "配送方式取值错误");
        assertEquals(99, item.getInt("sort").intValue(), "排序取值错误");
    }

    // ============ 2. 类型与格式：缩略图边界（空/单图/多图） ============

    /**
     * 列：用例名、images 原串、期望缩略图（null = 不得伪造任何值）
     */
    static Stream<Arguments> thumbnailCases() {
        return Stream.of(
                arguments("多图取首张", "https://img.test/a.jpg,https://img.test/b.jpg", "https://img.test/a.jpg"),
                arguments("单图原样返回", "https://img.test/only.jpg", "https://img.test/only.jpg"),
                arguments("空images不伪造", "", null),
                arguments("null-images不伪造", null, null)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("thumbnailCases")
    void 缩略图取images首图_非整串_空值不伪造(String name, String images, String expectedThumbnail) throws Exception {
        SkuPO po = fullSkuPo();
        po.setImages(images);
        when(skuService.queryPage(any())).thenReturn(new SkuPageResult(Collections.singletonList(po), 1));

        MvcResult result = mockMvc.perform(get("/product/list")).andReturn();
        JSONObject item = parseData(result.getResponse()).getJSONArray("list").getJSONObject(0);

        if (expectedThumbnail == null) {
            assertNull(item.getStr("thumbnail"), name + "：images 为空不得伪造缩略图");
        } else {
            assertEquals(expectedThumbnail, item.getStr("thumbnail"), name + "：缩略图必须为 images 第一张图");
        }
    }

    // ============ 2. 类型与格式：列表绑定 / 分页参数透传 ============

    @Test
    void deliveryMethods多选_列表绑定且筛选分页参数透传Service() throws Exception {
        when(skuService.queryPage(any())).thenReturn(new SkuPageResult(Collections.emptyList(), 0));

        mockMvc.perform(get("/product/list")
                .param("deliveryMethods", "CITY", "PICKUP")
                .param("name", "咖啡")
                .param("productType", "1")
                .param("type", "2")
                .param("status", "1")
                .param("categoryId", "7")
                .param("page", "5")
                .param("size", "20")).andReturn();

        ArgumentCaptor<SkuQuery> captor = ArgumentCaptor.forClass(SkuQuery.class);
        verify(skuService, only()).queryPage(captor.capture());
        SkuQuery query = captor.getValue();
        assertEquals(Arrays.asList("CITY", "PICKUP"), query.getDeliveryMethods(),
                "多选配送方式必须绑定为列表");
        assertEquals("咖啡", query.getName(), "name 筛选必须透传 Service");
        assertEquals(Integer.valueOf(1), query.getProductType(), "productType 筛选必须透传 Service");
        assertEquals(Integer.valueOf(2), query.getType(), "type 筛选必须透传 Service");
        assertEquals(Integer.valueOf(1), query.getStatus(), "status 筛选必须透传 Service");
        assertEquals(Integer.valueOf(7), query.getCategoryId(), "categoryId 筛选必须透传 Service");
        assertEquals(Integer.valueOf(5), query.getPage(), "page 必须透传 Service");
        assertEquals(Integer.valueOf(20), query.getSize(), "size 必须透传 Service");
    }

    // ============ 4. 非法输入：非法分页参数一律 400 中文消息 ============

    /**
     * 列：用例名、参数名、参数值、期望中文消息片段
     */
    static Stream<Arguments> illegalPagingCases() {
        return Stream.of(
                arguments("page=0拒绝", "page", "0", "页码"),
                arguments("page=-1拒绝", "page", "-1", "页码"),
                arguments("page非数字拒绝", "page", "abc", "分页"),
                arguments("size=0拒绝", "size", "0", "每页条数"),
                arguments("size=101拒绝", "size", "101", "每页条数"),
                arguments("size负数拒绝", "size", "-5", "每页条数"),
                arguments("size非数字拒绝", "size", "abc", "分页")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("illegalPagingCases")
    void 非法分页参数_返回400中文消息(String name, String param, String value, String messageFragment) throws Exception {
        when(skuService.queryPage(any())).thenReturn(new SkuPageResult(Collections.emptyList(), 0));

        MvcResult result = mockMvc.perform(get("/product/list").param(param, value)).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(400, response.getStatus(),
                name + "：非法分页参数必须拒绝为 400，实际：" + response.getStatus());
        String body = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains(messageFragment),
                name + "：400 响应应含中文原因「" + messageFragment + "」，实际：" + body);
    }

    // ============ 4. 非法输入：空结果 -> 空列表 + total=0，任何字段不伪造 ============

    @Test
    void 空结果_返回空列表与total为0_不伪造任何数值() throws Exception {
        when(skuService.queryPage(any())).thenReturn(new SkuPageResult(Collections.emptyList(), 0));

        MvcResult result = mockMvc.perform(get("/product/list")).andReturn();
        JSONObject data = parseData(result.getResponse());

        assertEquals(0L, data.getLong("total").longValue(), "空结果 total 必须为 0");
        JSONArray list = data.getJSONArray("list");
        assertEquals(0, list.size(), "空结果必须为空列表（前端渲染「暂无数据」）");
        assertEquals(new HashSet<>(Arrays.asList("list", "total")), data.keySet(),
                "空结果时 data 只允许 list/total，不得伪造任何行字段或数值");
    }

    // ============ 4. 非法输入：越权 shopId 被忽略，筛选照常透传（2.2 语义） ============

    @Test
    void 越权shopId参数被忽略_仍以登录shopId且筛选照常透传() throws Exception {
        when(skuService.queryPage(any())).thenReturn(new SkuPageResult(Collections.emptyList(), 0));

        mockMvc.perform(get("/product/list")
                .param("shopId", "99")
                .param("name", "咖啡")
                .param("status", "1")).andReturn();

        ArgumentCaptor<SkuQuery> captor = ArgumentCaptor.forClass(SkuQuery.class);
        verify(skuService, only()).queryPage(captor.capture());
        SkuQuery query = captor.getValue();
        assertEquals(Integer.valueOf(3), query.getShopId(),
                "shopId 必须恒取登录上下文，请求参数 shopId 一律忽略");
        assertEquals("咖啡", query.getName(), "越权参数被忽略时筛选必须照常透传");
        assertEquals(Integer.valueOf(1), query.getStatus(), "越权参数被忽略时状态筛选必须照常透传");
    }

    // ============ 3. 重复创建：同参数重复请求 -> 响应完全一致 ============

    @Test
    void 同参数重复请求_响应完全一致不串态() throws Exception {
        SkuPO po = fullSkuPo();
        when(skuService.queryPage(any())).thenReturn(new SkuPageResult(Collections.singletonList(po), 1));

        MvcResult first = mockMvc.perform(get("/product/list").param("name", "咖啡")).andReturn();
        MvcResult second = mockMvc.perform(get("/product/list").param("name", "咖啡")).andReturn();

        JSONObject firstData = parseData(first.getResponse());
        JSONObject secondData = parseData(second.getResponse());
        assertEquals(1L, firstData.getLong("total").longValue(), "total 应为条件命中总数");
        assertEquals("测试商品", firstData.getJSONArray("list").getJSONObject(0).getStr("name"),
                "行数据必须真实返回");
        assertEquals(firstData.toString(), secondData.toString(),
                "同参数重复请求必须返回完全一致的结果（幂等、不串态）");
        verify(skuService, times(2)).queryPage(any());
    }

    // ============ B 段：Service -> DAO 契约（queryPage 组装 rows+total） ============

    private SkuServiceImpl serviceWithMapper(SkuMapper mapper) {
        SkuServiceImpl service = new SkuServiceImpl();
        ReflectionTestUtils.setField(service, "skuMapper", mapper);
        return service;
    }

    @Test
    void queryPage_组装当前页rows与条件命中总数() {
        SkuMapper mapper = mock(SkuMapper.class);
        SkuQuery query = new SkuQuery();
        query.setShopId(3);
        SkuPO po = fullSkuPo();
        when(mapper.selectPage(query)).thenReturn(Collections.singletonList(po));
        when(mapper.countByFilter(query)).thenReturn(1);

        SkuPageResult result = serviceWithMapper(mapper).queryPage(query);

        assertEquals(1, result.getRows().size(), "rows 应为当前页数据行");
        assertEquals(po, result.getRows().get(0), "rows 应原样返回 DAO 命中行");
        assertEquals(1, result.getTotal(), "total 应为条件命中总数");
    }

    @Test
    void queryPage_空结果_rows为空且total为0_不伪造() {
        SkuMapper mapper = mock(SkuMapper.class);
        SkuQuery query = new SkuQuery();
        query.setShopId(3);
        when(mapper.selectPage(query)).thenReturn(Collections.emptyList());
        when(mapper.countByFilter(query)).thenReturn(0);

        SkuPageResult result = serviceWithMapper(mapper).queryPage(query);

        assertEquals(0, result.getRows().size(), "空结果 rows 必须为空");
        assertEquals(0, result.getTotal(), "空结果 total 必须为 0");
    }

    @Test
    void queryPage_筛选与shopId原样透传DAO() {
        SkuMapper mapper = mock(SkuMapper.class);
        SkuQuery query = new SkuQuery();
        query.setShopId(3);
        query.setName("咖啡");
        query.setPage(2);
        query.setSize(20);
        when(mapper.selectPage(any())).thenReturn(Collections.emptyList());
        when(mapper.countByFilter(any())).thenReturn(0);

        serviceWithMapper(mapper).queryPage(query);

        ArgumentCaptor<SkuQuery> captor = ArgumentCaptor.forClass(SkuQuery.class);
        verify(mapper).selectPage(captor.capture());
        verify(mapper).countByFilter(captor.capture());
        for (SkuQuery daoQuery : captor.getAllValues()) {
            assertEquals(Integer.valueOf(3), daoQuery.getShopId(), "shopId 必须原样透传 DAO");
            assertEquals("咖啡", daoQuery.getName(), "name 筛选必须原样透传 DAO");
            assertEquals(Integer.valueOf(2), daoQuery.getPage(), "page 必须原样透传 DAO");
            assertEquals(Integer.valueOf(20), daoQuery.getSize(), "size 必须原样透传 DAO");
        }
    }
}
