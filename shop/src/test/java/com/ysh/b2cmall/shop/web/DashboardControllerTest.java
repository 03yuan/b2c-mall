package com.ysh.b2cmall.shop.web;

import com.ysh.b2cmall.shop.feign.OrderStatsClient;
import com.ysh.b2cmall.shop.feign.ProductStatsClient;
import com.ysh.b2cmall.shop.service.DashboardService;
import com.ysh.b2cmall.shop.service.impl.DashboardServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /shop/dashboard/summary 表格驱动测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-dashboard.md 功能上下文 1.1（登录商家只看到自己店铺的数据）：
 * - 1.1.1 不带 Authorization 头请求 dashboard 接口 -> 401（Fail 边界：误加白名单则未登录可看经营数据）
 * - 1.1.2 shopId 只从登录态取（网关解析 token 后透传的 X-Shop-Id），
 *   请求参数 shopId 被忽略（Fail 边界：信任客户端传入的 shopId 即产生水平越权）
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：登录态字段（Authorization / X-Shop-Id）缺失或为空 -> 401
 * 2. 类型与格式：X-Shop-Id 非数字/小数/负数/0/溢出、Authorization 格式非法 -> 401；
 *    请求参数 shopId 类型非法 -> 参数被忽略，不 500
 * 3. 非法输入：越权 shopId 参数、注入串、空值、未知参数 -> 一律忽略，按登录店铺查询
 * 4. 重复创建：dashboard 为只读查询接口无创建语义，此处映射为
 *    「重复请求幂等、无副作用」——重复调用仍只查登录店铺数据，不产生额外数据
 *
 * 功能上下文 1.2（统一入口 VO 区块 + shopId 注入与数据隔离）测试在类末尾单独一节，
 * 用真实 DashboardServiceImpl + mock 下游统计客户端，验证：
 * - 1.2.1 `GET /shop/dashboard/summary` 一次返回 cards/trend/metrics 三个区块
 * - 1.2.2 shopId 由登录上下文注入，传给下游统计接口的参数恒为登录 shopId
 *
 * 功能上下文 2.3（shop-service 聚合层：Feign 客户端 + Service）测试在类末尾单独一节：
 * - 2.3.1 OrderStatsClient / ProductStatsClient 请求路径与 shopId 参数正确
 *   （Fail 边界：Feign URL 配错 500）
 * - 2.3.2 组装 6 个卡片数值，无数据（0/缺 key）时为 null 而非 0（前端「暂无数据」）
 *   （Fail 边界：返回 0 会让前端显示伪造感数字）
 * 下游统计 map 的 key 契约：订单侧 pendingPayment/pendingShipment/pendingReceipt/completed/refundAfterSale，
 * 商品侧 stockAlert。
 *
 * 功能上下文 3.2（shop-service 趋势 VO 聚合）测试在类末尾单独一节：
 * - 3.2.1 趋势 VO 含 labels/today/yesterday 三组且各 24 点，供前端悬浮显示该小时数值
 *   （Fail 边界：字段名/顺序不匹配前端渲染错乱）
 * 下游统计 map 的趋势 key 契约：todayHourly/yesterdayHourly（各 24 点序列，全天无支付为 null）。
 *
 * 功能上下文 4.2（shop-service 同比计算纯函数）测试在类末尾单独一节：
 * - 4.2.1 同比=(今-昨)/昨×100%，格式化两位小数（+100.00%/-50.00%），正数带 + 号
 *   （Fail 边界：正数不带 + 号；小数位截断错误）
 * - 4.2.2 昨日无数据（null 或 0）显示 100%（需求硬规定），今日无数据（null 或 0）整项 null（暂无数据）
 *   （Fail 边界：除零抛异常返回 500）
 */
class DashboardControllerTest {

    private final DashboardService dashboardService = mock(DashboardService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new DashboardController(dashboardService))
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();

    /**
     * 列：用例名、Authorization 头、X-Shop-Id 头、查询参数名、查询参数值、
     *     期望 HTTP 状态、期望传给 DashboardService 的 shopId（null=不应调用服务）
     */
    static Stream<Arguments> summaryCases() {
        return Stream.of(
                // ----1. 字段级校验：登录态字段缺失/为空 -> 401----
                arguments("缺Authorization头", null, "1", null, null, 401, null),
                arguments("Authorization为空串", "", "1", null, null, 401, null),
                arguments("缺X-Shop-Id头", "Bearer tok", null, null, null, 401, null),
                arguments("X-Shop-Id为空串", "Bearer tok", "", null, null, 401, null),

                // ----2. 类型与格式----
                arguments("X-Shop-Id非数字", "Bearer tok", "abc", null, null, 401, null),
                arguments("X-Shop-Id为小数", "Bearer tok", "1.5", null, null, 401, null),
                arguments("X-Shop-Id为负数", "Bearer tok", "-1", null, null, 401, null),
                arguments("X-Shop-Id为0", "Bearer tok", "0", null, null, 401, null),
                arguments("X-Shop-Id超长溢出", "Bearer tok", "99999999999999999999", null, null, 401, null),
                arguments("Authorization格式非法非Bearer", "Basic abc", "1", null, null, 401, null),
                arguments("shopId参数类型非法被忽略不500", "Bearer tok", "1", "shopId", "abc", 200, 1),

                // ----3. 非法输入：参数 shopId 一律忽略，只查登录店铺----
                arguments("越权shopId参数被忽略", "Bearer tok", "1", "shopId", "2", 200, 1),
                arguments("shopId参数注入串被忽略", "Bearer tok", "1", "shopId", "1' or '1'='1", 200, 1),
                arguments("shopId参数负数被忽略", "Bearer tok", "1", "shopId", "-9", 200, 1),
                arguments("shopId参数空值被忽略", "Bearer tok", "1", "shopId", "", 200, 1),
                arguments("未知多余参数被忽略", "Bearer tok", "1", "foo", "bar", 200, 1)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("summaryCases")
    void summary(String caseName, String authorization, String shopIdHeader,
                 String paramName, String paramValue, int expectedStatus,
                 Integer expectedServiceShopId) throws Exception {
        MockHttpServletRequestBuilder req = get("/shop/dashboard/summary");
        if (authorization != null) {
            req.header("Authorization", authorization);
        }
        if (shopIdHeader != null) {
            req.header("X-Shop-Id", shopIdHeader);
        }
        if (paramName != null) {
            req.param(paramName, paramValue);
        }

        ResultActions actions = mockMvc.perform(req);
        actions.andExpect(status().is(expectedStatus));

        if (expectedStatus == 401) {
            actions.andExpect(jsonPath("$.message").value(containsString("登录")));
            verifyNoInteractions(dashboardService);
        } else {
            // 数据隔离：下游统计只按登录 shopId 查询，请求参数里的 shopId 被忽略
            verify(dashboardService, only()).getSummary(expectedServiceShopId);
        }
    }

    // ============ 4. 重复创建（查询接口无创建语义 -> 重复请求幂等、无副作用）============

    @Test
    void summary_重复请求_幂等且无副作用() throws Exception {
        mockMvc.perform(get("/shop/dashboard/summary")
                        .header("Authorization", "Bearer tok")
                        .header("X-Shop-Id", "1"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/shop/dashboard/summary")
                        .header("Authorization", "Bearer tok")
                        .header("X-Shop-Id", "1"))
                .andExpect(status().isOk());

        // 两次请求都只按登录 shopId=1 查询，重复请求不产生额外数据/副作用
        verify(dashboardService, times(2)).getSummary(1);
    }

    // ============ 功能上下文 1.2：统一入口 VO 区块 + shopId 注入与数据隔离 ============

    private final OrderStatsClient orderStatsClient = mock(OrderStatsClient.class);
    private final ProductStatsClient productStatsClient = mock(ProductStatsClient.class);

    /** 1.2 的测试对象：Controller + 真实 DashboardServiceImpl + mock 下游统计客户端 */
    private MockMvc summaryMvc() {
        return MockMvcBuilders
                .standaloneSetup(new DashboardController(
                        new DashboardServiceImpl(orderStatsClient, productStatsClient)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    /**
     * 列：用例名、Authorization 头、X-Shop-Id 头、查询参数名、查询参数值、
     *     期望 HTTP 状态、期望注入/下发的 shopId（null=不应调用下游）
     */
    static Stream<Arguments> summaryVoCases() {
        return Stream.of(
                // ----1. 字段级校验：统一入口一次返回 cards/trend/metrics 三区块，shopId 由登录上下文注入----
                arguments("VO区块齐全-登录店铺1", "Bearer tok", "1", null, null, 200, 1),
                arguments("VO区块齐全-登录店铺7", "Bearer tok", "7", null, null, 200, 7),

                // ----2. 类型与格式：shopId 注入值为数字、三区块为对象、参数类型非法不致 500----
                arguments("shopId注入为数字-大数值", "Bearer tok", "42", null, null, 200, 42),
                arguments("shopId参数类型非法被忽略", "Bearer tok", "1", "shopId", "abc", 200, 1),
                arguments("shopId参数小数被忽略", "Bearer tok", "1", "shopId", "1.5", 200, 1),

                // ----3. 非法输入：参数 shopId 一律忽略，下游统计接口只收登录 shopId----
                arguments("越权shopId参数被忽略", "Bearer tok", "1", "shopId", "2", 200, 1),
                arguments("shopId参数注入串被忽略", "Bearer tok", "1", "shopId", "1' or '1'='1", 200, 1),
                arguments("缺登录态不查下游", null, null, null, null, 401, null)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("summaryVoCases")
    void summary_统一入口VO与shopId注入(String caseName, String authorization, String shopIdHeader,
                                       String paramName, String paramValue, int expectedStatus,
                                       Integer expectedShopId) throws Exception {
        MockHttpServletRequestBuilder req = get("/shop/dashboard/summary");
        if (authorization != null) {
            req.header("Authorization", authorization);
        }
        if (shopIdHeader != null) {
            req.header("X-Shop-Id", shopIdHeader);
        }
        if (paramName != null) {
            req.param(paramName, paramValue);
        }

        ResultActions actions = summaryMvc().perform(req);
        actions.andExpect(status().is(expectedStatus));

        if (expectedStatus == 401) {
            actions.andExpect(jsonPath("$.message").value(containsString("登录")));
            verifyNoInteractions(orderStatsClient, productStatsClient);
        } else {
            // VO 各区块齐全 + shopId 由登录上下文注入（数字类型），三区块均为对象
            actions.andExpect(jsonPath("$.data.shopId").value(expectedShopId))
                    .andExpect(jsonPath("$.data.shopId").isNumber())
                    .andExpect(jsonPath("$.data.cards").isMap())
                    .andExpect(jsonPath("$.data.trend").isMap())
                    .andExpect(jsonPath("$.data.metrics").isMap());
            // 数据隔离：mock 下游返回不同店铺数据，但传给统计接口的参数恒为登录 shopId
            verify(orderStatsClient, only()).getStats(expectedShopId);
            verify(productStatsClient, only()).getStats(expectedShopId);
        }
    }

    // ============ 4. 重复创建（查询接口无创建语义 -> 重复请求幂等、无副作用）============

    @Test
    void summary_重复请求_幂等且下游恒按登录shopId() throws Exception {
        summaryMvc().perform(get("/shop/dashboard/summary")
                        .header("Authorization", "Bearer tok")
                        .header("X-Shop-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shopId").value(1));

        summaryMvc().perform(get("/shop/dashboard/summary")
                        .header("Authorization", "Bearer tok")
                        .header("X-Shop-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shopId").value(1));

        // 重复请求幂等：下游统计接口两次都只按登录 shopId=1 查询
        verify(orderStatsClient, times(2)).getStats(1);
        verify(productStatsClient, times(2)).getStats(1);
    }

    // ============ 功能上下文 2.3：Feign 客户端契约 + 6 卡片组装（暂无数据=null） ============

    /** 6 个卡片字段（JSON 字段名）：前 5 个来自订单统计，第 6 个来自商品统计 */
    private static final String[] CARD_FIELDS = {
            "pendingPayment", "pendingShipment", "pendingReceipt", "completed", "refundAfterSale", "stockAlert"};

    private static final String[] ORDER_KEYS = {
            "pendingPayment", "pendingShipment", "pendingReceipt", "completed", "refundAfterSale"};

    private static final String[] PRODUCT_KEYS = {"stockAlert"};

    /** 下游统计 map 构造：spec 逗号分隔，"-"=该 key 缺省；恒带一个未知多余 key（非法输入防护） */
    private Map<String, Object> buildMap(String[] keys, String spec) {
        Map<String, Object> map = new HashMap<>();
        map.put("unknownMetric", 999);
        String[] values = spec.split(",", -1);
        for (int i = 0; i < keys.length; i++) {
            if (!"-".equals(values[i])) {
                map.put(keys[i], Integer.parseInt(values[i]));
            }
        }
        return map;
    }

    // ----2.3.1 Feign 客户端契约：请求路径与 shopId 参数正确（Fail 边界：Feign URL 配错 500）----

    /**
     * 列：用例名、Feign 客户端接口、期望服务名、期望 path
     */
    static Stream<Arguments> feignClientCases() {
        return Stream.of(
                arguments("订单统计客户端", OrderStatsClient.class, "order-service", "/order"),
                arguments("商品统计客户端", ProductStatsClient.class, "product-service", "/product")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("feignClientCases")
    void feign客户端_请求路径与shopId参数正确(String caseName, Class<?> client,
                                              String expectedServiceName, String expectedPath) throws Exception {
        FeignClient feignClient = client.getAnnotation(FeignClient.class);
        assertEquals(expectedServiceName, feignClient.name(), "服务名配错将无法路由到下游");
        assertEquals(expectedPath, feignClient.path(), "path 配错则请求路径错误（500）");

        Method method = client.getMethod("getStats", Integer.class);
        assertEquals("/stats/summary", method.getAnnotation(GetMapping.class).value()[0],
                "统计接口路径应为 /stats/summary");
        assertEquals("shopId", method.getParameters()[0].getAnnotation(RequestParam.class).value(),
                "统计查询参数名应为 shopId");
    }

    // ----2.3.2 组装 6 个卡片数值：0/缺 key -> null（暂无数据），有数据 -> 真实数字----

    /**
     * 列：用例名、订单侧统计值（5 项逗号分隔，"-"=缺 key）、商品侧统计值（1 项）、
     *     期望 6 个卡片值（逗号分隔，"null"=期望暂无数据）
     */
    static Stream<Arguments> cardsCases() {
        return Stream.of(
                // ----1. 字段级校验：六个卡片字段与下游 key 一一对应，不串位----
                arguments("六项均有数据原样映射", "12,2,24,1,3", "6", "12,2,24,1,3,6"),

                // ----2. 类型与格式：数值保持数字类型（runner 断言 isNumber）；缺 key 视为无数据----
                arguments("缺key视为暂无数据", "-,-,-,-,-", "-", "null,null,null,null,null,null"),

                // ----3. 重复创建（查询接口无创建语义 -> 重复请求数值一致、幂等）见下方独立测试----

                // ----4. 非法输入：0 不得伪造为数字 0、0 与缺 key 混排、多余未知 key 被忽略----
                arguments("全0视为暂无数据全为null", "0,0,0,0,0", "0", "null,null,null,null,null,null"),
                arguments("0与缺key混有数据", "0,7,-,0,2", "0", "null,7,null,null,2,null")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cardsCases")
    void summary_6卡片组装与暂无数据语义(String caseName, String orderSpec, String productSpec,
                                          String expectedSpec) throws Exception {
        when(orderStatsClient.getStats(1)).thenReturn(buildMap(ORDER_KEYS, orderSpec));
        when(productStatsClient.getStats(1)).thenReturn(buildMap(PRODUCT_KEYS, productSpec));

        ResultActions actions = summaryMvc().perform(get("/shop/dashboard/summary")
                .header("Authorization", "Bearer tok")
                .header("X-Shop-Id", "1"));
        actions.andExpect(status().isOk()).andExpect(jsonPath("$.data.cards").isMap());

        String[] expected = expectedSpec.split(",", -1);
        for (int i = 0; i < CARD_FIELDS.length; i++) {
            String path = "$.data.cards." + CARD_FIELDS[i];
            if ("null".equals(expected[i])) {
                actions.andExpect(jsonPath(path).value(nullValue()));
            } else {
                actions.andExpect(jsonPath(path).isNumber())
                        .andExpect(jsonPath(path).value(Integer.parseInt(expected[i])));
            }
        }
        verify(orderStatsClient, only()).getStats(1);
        verify(productStatsClient, only()).getStats(1);
    }

    @Test
    void summary_重复请求_卡片数值一致且幂等() throws Exception {
        when(orderStatsClient.getStats(1)).thenReturn(buildMap(ORDER_KEYS, "12,2,24,1,3"));
        when(productStatsClient.getStats(1)).thenReturn(buildMap(PRODUCT_KEYS, "6"));

        for (int i = 0; i < 2; i++) {
            summaryMvc().perform(get("/shop/dashboard/summary")
                            .header("Authorization", "Bearer tok")
                            .header("X-Shop-Id", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.cards.pendingPayment").value(12))
                    .andExpect(jsonPath("$.data.cards.refundAfterSale").value(3))
                    .andExpect(jsonPath("$.data.cards.stockAlert").value(6));
        }
        verify(orderStatsClient, times(2)).getStats(1);
        verify(productStatsClient, times(2)).getStats(1);
    }

    // ============ 功能上下文 3.2：趋势 VO 聚合（labels/today/yesterday 三组各 24 点） ============

    /** 趋势序列 spec："-"=缺 key；"null"=值为 null；"empty"=空数组；"data"=0~23 的 24 点数据（顺序可验） */
    private void putTrend(Map<String, Object> stats, String key, String spec) {
        if ("-".equals(spec)) {
            return;
        }
        if ("null".equals(spec)) {
            stats.put(key, null);
            return;
        }
        List<Integer> series = new ArrayList<>();
        if ("data".equals(spec)) {
            for (int i = 0; i < 24; i++) {
                series.add(i);
            }
        }
        stats.put(key, series);
    }

    /** "data" -> 断言 24 点数组且顺序为 0~23；其余（缺 key/null/空数组）-> 断言为 null（暂无数据） */
    private void assertSeries(ResultActions actions, String path, String spec) throws Exception {
        if ("data".equals(spec)) {
            actions.andExpect(jsonPath(path).isArray())
                    .andExpect(jsonPath(path + ".length()").value(24))
                    .andExpect(jsonPath(path + "[0]").value(0))
                    .andExpect(jsonPath(path + "[13]").value(13))
                    .andExpect(jsonPath(path + "[23]").value(23));
        } else {
            actions.andExpect(jsonPath(path).value(nullValue()));
        }
    }

    /**
     * 列：用例名、今日序列 spec、昨日序列 spec
     */
    static Stream<Arguments> trendCases() {
        return Stream.of(
                // ----1. 字段级校验：响应含 labels/today/yesterday 三组，两序列各 24 点----
                arguments("今日昨日均有数据", "data", "data"),

                // ----2. 类型与格式：labels 固定 24 点顺序 0:00~23:00（runner 断言）；序列为数组----
                arguments("仅今日有数据", "data", "-"),

                // ----3. 重复创建（查询接口无创建语义 -> 重复请求幂等）见下方独立测试----

                // ----4. 非法输入：无数据（缺 key/null/空数组）一律 null（暂无数据），不得伪造 0 曲线----
                arguments("今日缺key昨日有数据", "-", "data"),
                arguments("今日null昨日有数据", "null", "data"),
                arguments("今日空数组视为无数据", "empty", "data"),
                arguments("两日均无数据labels仍24点", "-", "-"),
                arguments("昨日空数组今日null", "null", "empty")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("trendCases")
    void summary_趋势区块labels与今日昨日序列(String caseName, String todaySpec,
                                              String yesterdaySpec) throws Exception {
        Map<String, Object> stats = buildMap(ORDER_KEYS, "-,-,-,-,-");
        putTrend(stats, "todayHourly", todaySpec);
        putTrend(stats, "yesterdayHourly", yesterdaySpec);
        when(orderStatsClient.getStats(1)).thenReturn(stats);
        when(productStatsClient.getStats(1)).thenReturn(buildMap(PRODUCT_KEYS, "-"));

        ResultActions actions = summaryMvc().perform(get("/shop/dashboard/summary")
                .header("Authorization", "Bearer tok")
                .header("X-Shop-Id", "1"));
        actions.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trend").isMap())
                // labels：固定 24 点、顺序 0:00~23:00（前端 X 轴不错位）
                .andExpect(jsonPath("$.data.trend.labels").isArray())
                .andExpect(jsonPath("$.data.trend.labels.length()").value(24))
                .andExpect(jsonPath("$.data.trend.labels[0]").value("0:00"))
                .andExpect(jsonPath("$.data.trend.labels[9]").value("9:00"))
                .andExpect(jsonPath("$.data.trend.labels[23]").value("23:00"));

        assertSeries(actions, "$.data.trend.today", todaySpec);
        assertSeries(actions, "$.data.trend.yesterday", yesterdaySpec);

        verify(orderStatsClient, only()).getStats(1);
        verify(productStatsClient, only()).getStats(1);
    }

    // ----3. 重复创建（查询接口无创建语义 -> 重复请求幂等、无副作用）----

    @Test
    void summary_重复请求_趋势序列一致且幂等() throws Exception {
        Map<String, Object> stats = buildMap(ORDER_KEYS, "-,-,-,-,-");
        putTrend(stats, "todayHourly", "data");
        putTrend(stats, "yesterdayHourly", "data");
        when(orderStatsClient.getStats(1)).thenReturn(stats);
        when(productStatsClient.getStats(1)).thenReturn(buildMap(PRODUCT_KEYS, "-"));

        for (int i = 0; i < 2; i++) {
            summaryMvc().perform(get("/shop/dashboard/summary")
                            .header("Authorization", "Bearer tok")
                            .header("X-Shop-Id", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.trend.labels.length()").value(24))
                    .andExpect(jsonPath("$.data.trend.today[23]").value(23))
                    .andExpect(jsonPath("$.data.trend.yesterday[0]").value(0));
        }
        verify(orderStatsClient, times(2)).getStats(1);
        verify(productStatsClient, times(2)).getStats(1);
    }

    // ============ 功能上下文 4.2：同比计算纯函数（(今-昨)/昨×100%，格式化 +100.00%）============

    /** 4.2 的测试对象：纯函数直调 Service 实现，不经过 HTTP 与下游客户端 */
    private final DashboardServiceImpl dashboardImpl =
            new DashboardServiceImpl(orderStatsClient, productStatsClient);

    /** 数值 spec："-"=null（无数据），其余为数字字符串 */
    private static BigDecimal num(String spec) {
        return "-".equals(spec) ? null : new BigDecimal(spec);
    }

    /**
     * 列：用例名、今日值 spec、昨日值 spec、期望同比字符串（"-"=期望 null）
     */
    static Stream<Arguments> ratioCases() {
        return Stream.of(
                // ----1. 字段级校验：今日/昨日无数据（null）语义----
                arguments("今日null整项null", "-", "1", "-"),
                arguments("昨日null显示100%", "2", "-", "100%"),

                // ----2. 类型与格式：(今-昨)/昨×100%，正数带 + 号、两位小数
                //      （Fail 边界：正数不带 + 号；小数位截断错误）----
                arguments("增长带正号两位小数", "2", "1", "+100.00%"),
                arguments("下降带负号两位小数", "1", "2", "-50.00%"),
                arguments("零变化带符号两位小数", "1", "1", "+0.00%"),
                arguments("四舍五入两位小数", "1", "3", "-66.67%"),

                // ----3. 重复创建（纯函数无状态 -> 重复计算幂等）见下方独立测试----

                // ----4. 非法输入：昨日 0 不得抛除零异常 500；今日 0 与卡片 0->null 语义一致视为无数据----
                arguments("昨日0显示100%不抛除零", "2", "0", "100%"),
                arguments("今日0视为无数据整项null", "0", "1", "-")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ratioCases")
    void ratio_同比公式与格式化(String caseName, String todaySpec, String yesterdaySpec,
                              String expected) {
        String actual = dashboardImpl.ratio(num(todaySpec), num(yesterdaySpec));
        if ("-".equals(expected)) {
            assertEquals(null, actual, "今日无数据整项应为 null（前端「暂无数据」）");
        } else {
            assertEquals(expected, actual);
        }
    }

    // ----3. 重复创建（纯函数无状态 -> 重复计算结果一致、幂等、无副作用）----

    @Test
    void ratio_重复计算_结果一致且幂等() {
        String first = dashboardImpl.ratio(num("2"), num("1"));
        assertEquals("+100.00%", first, "重复计算前结果本身必须正确");

        String second = dashboardImpl.ratio(num("2"), num("1"));
        assertEquals(first, second, "纯函数重复计算应完全一致、无副作用");
    }
}
