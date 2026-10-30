package com.ysh.order.web;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ysh.order.bean.CurrentEmployeeBean;
import com.ysh.order.component.RequestHolderComponent;
import com.ysh.order.dao.OrderMapper;
import com.ysh.order.dao.RefundMapper;
import com.ysh.order.dao.po.OrderPO;
import com.ysh.order.dao.po.RefundPO;
import com.ysh.order.service.impl.OrderServiceImpl;
import com.ysh.order.web.advice.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * GET /order/page 操作列判定字段的 HTTP 契约测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 6.2（操作列展示条件 - 后端供判定字段，前端渲染）：
 * - 6.2.1 VO 同时提供 status、refundStatus 原始枚举值（非仅中文文案），前端据此控制
 *   「发货」（仅待发货 status=PAID）、「退款信息」（refundStatus != NONE）按钮显隐
 *   （Fail 边界：只给中文文案 -> 前端用文案判逻辑，文案一改按钮失效；
 *     枚举值被中文化或被规范化为未知 -> 前端无法判定）
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：JSON 行内含 status / refundStatus 两个字段，且取值为枚举名（全大写）
 * 2. 类型与格式：无 statusText/statusName 等中文文案字段；createTime 为 ISO-8601 字符串；
 *    列表行不含明文 receiverPhone（只有 receiverPhoneMasked）
 * 3. 重复创建：同请求两次 JSON 完全一致；状态流转后 status 与派生标签同步变化、无残留
 * 4. 非法输入：未知状态值原样回传（不中文化/不置 null）；未知退款状态原样回传且不派生「退款申请中」
 *
 * 被测链路为真实 OrderServiceImpl（mock OrderMapper / RefundMapper）+ standalone MockMvc，
 * 断言点为前端实际消费的 JSON，而非 Java 对象字段。
 */
class OrderRowActionContractTest {

    private static final Integer SHOP_ID = 3;

    private OrderMapper orderMapper;
    private RefundMapper refundMapper;
    private OrderServiceImpl orderService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        refundMapper = mock(RefundMapper.class);
        orderService = new OrderServiceImpl();
        ReflectionTestUtils.setField(orderService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(orderService, "refundMapper", refundMapper);

        RequestHolderComponent requestHolder = mock(RequestHolderComponent.class);
        CurrentEmployeeBean login = new CurrentEmployeeBean();
        login.setEmployeeId(7);
        login.setShopId(SHOP_ID);
        login.setUsername("tom");
        when(requestHolder.get()).thenReturn(login);

        // 与生产一致：日期按 ISO-8601 字符串输出而非时间戳数组
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(
                Jackson2ObjectMapperBuilder.json()
                        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .build());

        mockMvc = MockMvcBuilders.standaloneSetup(new OrderQueryController(orderService, requestHolder))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(converter)
                .build();
    }

    private OrderPO po(String status) {
        OrderPO po = new OrderPO();
        po.setId(1);
        po.setOrderNo("NO-A1");
        po.setShopId(SHOP_ID);
        po.setStatus(status);
        po.setSkuId(100);
        po.setSkuName("测试商品");
        po.setSkuImage("https://img.example.com/a.png");
        po.setUnitPrice(new BigDecimal("19.90"));
        po.setQuantity(2);
        po.setTotalPrice(new BigDecimal("39.80"));
        po.setPayType("WECHAT");
        po.setBuyerUsername("buyer_a");
        po.setReceiverName("张三");
        po.setReceiverPhone("13800005678");
        po.setStoreName("一号门店");
        po.setOrderCategory("NORMAL");
        po.setLogisticsType("EXPRESS");
        po.setIsGift(0);
        po.setCreateTime(LocalDateTime.of(2026, 10, 20, 10, 0, 0));
        return po;
    }

    private RefundPO refund(int id, int orderId, String status) {
        RefundPO refund = new RefundPO();
        refund.setId(id);
        refund.setOrderId(orderId);
        refund.setShopId(SHOP_ID);
        refund.setStatus(status);
        return refund;
    }

    /** 发起一次列表查询并返回 data.list[0] 的 JSON */
    private JSONObject firstRow(OrderPO po, RefundPO... refunds) throws Exception {
        when(orderMapper.selectPage(any(), anyInt(), anyInt())).thenReturn(Collections.singletonList(po));
        when(orderMapper.countByFilter(any())).thenReturn(1);
        when(refundMapper.selectByOrderIds(any())).thenReturn(Arrays.asList(refunds));
        return fetchFirstRowJson();
    }

    private JSONObject fetchFirstRowJson() throws Exception {
        MvcResult result = mockMvc.perform(get("/order/page")).andReturn();
        MockHttpServletResponse response = result.getResponse();
        assertEquals(200, response.getStatus(), "查询应返回 200");
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        JSONObject data = JSONUtil.parseObj(body).getJSONObject("data");
        assertNotNull(data, "响应 data 不得为 null，实际：" + body);
        JSONArray list = data.getJSONArray("list");
        assertNotNull(list, "响应 list 必须存在，实际：" + body);
        assertEquals(1, list.size(), "应返回 1 行，实际：" + body);
        return list.getJSONObject(0);
    }

    // ============ 1. 字段级校验：status / refundStatus 为原始枚举名 ============

    /**
     * 列：用例名、订单状态、退款单据状态、期望 status 枚举名、期望 refundStatus 枚举名
     */
    static Stream<Arguments> rawEnumCases() {
        return Stream.of(
                arguments("待付款PENDING", "PENDING", null, "PENDING", "NONE"),
                arguments("待发货PAID", "PAID", null, "PAID", "NONE"),
                arguments("待收货SHIPPED", "SHIPPED", null, "SHIPPED", "NONE"),
                arguments("交易成功COMPLETED", "COMPLETED", null, "COMPLETED", "NONE"),
                arguments("交易失败FAILED", "FAILED", null, "FAILED", "NONE"),
                arguments("待成团GROUP_PENDING", "GROUP_PENDING", null, "GROUP_PENDING", "NONE"),
                arguments("退款申请中PROCESSING", "SHIPPED", "PROCESSING", "SHIPPED", "PROCESSING"),
                arguments("退款完成DONE", "COMPLETED", "DONE", "COMPLETED", "DONE")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rawEnumCases")
    void 行JSON含原始枚举值(String caseName, String status, String refundStatus,
                         String expectedStatus, String expectedRefundStatus) throws Exception {
        RefundPO refund = refundStatus == null ? null : refund(1, 1, refundStatus);
        JSONObject row = refund == null ? firstRow(po(status)) : firstRow(po(status), refund);

        assertTrue(row.containsKey("status"), caseName + "：行 JSON 必须含 status 字段");
        assertTrue(row.containsKey("refundStatus"), caseName + "：行 JSON 必须含 refundStatus 字段");
        assertEquals(expectedStatus, row.getStr("status"), caseName + "：status 必须为原始枚举名");
        assertEquals(expectedRefundStatus, row.getStr("refundStatus"),
                caseName + "：refundStatus 必须为原始枚举名");
        assertEquals(expectedStatus, row.getStr("status").toUpperCase(),
                caseName + "：枚举名应为全大写（前端按常量比较，不得被中文化/小写化）");
    }

    // ============ 2. 类型与格式：无中文文案字段、日期格式、手机号口径 ============

    @Test
    void 不提供中文文案字段_前端按枚举值判定() throws Exception {
        JSONObject row = firstRow(po("PAID"));

        for (String textField : Arrays.asList("statusText", "statusName", "statusDesc",
                "refundStatusText", "refundStatusName", "refundStatusDesc")) {
            assertFalse(row.containsKey(textField),
                    "不得提供文案字段 " + textField + "（文案一改按钮逻辑即失效），实际键集：" + row.keySet());
        }
        assertEquals("PAID", row.getStr("status"), "判定依据必须是枚举名 PAID");
        assertFalse("待发货".equals(row.getStr("status")), "status 不得是中文字段");
    }

    @Test
    void 发货按钮判定依据_仅PAID为真() throws Exception {
        assertTrue("PAID".equals(firstRow(po("PAID")).getStr("status")),
                "待发货订单 status=PAID，前端据此显示「发货」按钮");

        for (String other : Arrays.asList("PENDING", "SHIPPED", "COMPLETED", "FAILED", "GROUP_PENDING")) {
            assertNotEquals("PAID", firstRow(po(other)).getStr("status"),
                    other + " 订单不得被前端判定为可发货");
        }
    }

    @Test
    void 退款信息按钮判定依据_非NONE即有售后() throws Exception {
        assertEquals("NONE", firstRow(po("PAID")).getStr("refundStatus"),
                "无售后时 refundStatus=NONE，前端隐藏「退款信息」按钮");
        assertEquals("PROCESSING", firstRow(po("PAID"), refund(1, 1, "PROCESSING")).getStr("refundStatus"),
                "退款申请中时前端显示「退款信息」按钮");
        assertEquals("DONE", firstRow(po("COMPLETED"), refund(1, 1, "DONE")).getStr("refundStatus"),
                "退款完成时前端仍显示「退款信息」按钮（可查看结果）");
    }

    @Test
    void 下单时间序列化为ISO字符串_前端可直接解析() throws Exception {
        JSONObject row = firstRow(po("PAID"));

        Object createTime = row.get("createTime");
        assertNotNull(createTime, "createTime 必须返回");
        assertTrue(createTime instanceof CharSequence,
                "createTime 应序列化为 ISO-8601 字符串而非时间戳数组，实际：" + createTime);
        assertTrue(createTime.toString().startsWith("2026-10-20T10:00"),
                "createTime 格式应为 yyyy-MM-ddTHH:mm:ss，实际：" + createTime);
    }

    @Test
    void 列表行不含明文手机号_只有脱敏字段() throws Exception {
        JSONObject row = firstRow(po("PAID"));

        assertTrue(row.containsKey("receiverPhoneMasked"), "必须提供脱敏手机号字段");
        assertEquals("138****5678", row.getStr("receiverPhoneMasked"), "脱敏格式错误");
        assertFalse(row.containsKey("receiverPhone"), "不得返回明文手机号字段 receiverPhone");
        assertFalse(JSONUtil.toJsonStr(row).contains("13800005678"), "行 JSON 不得出现明文手机号");
    }

    // ============ 3. 重复创建：重复请求一致、状态流转后无残留 ============

    @Test
    void 同请求重复执行_JSON完全一致() throws Exception {
        when(orderMapper.selectPage(any(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(po("PAID")));
        when(orderMapper.countByFilter(any())).thenReturn(1);
        when(refundMapper.selectByOrderIds(any())).thenReturn(Collections.emptyList());

        JSONObject first = fetchFirstRowJson();
        JSONObject second = fetchFirstRowJson();

        assertEquals(first.toString(), second.toString(), "同请求重复执行 JSON 必须完全一致");
    }

    @Test
    void 状态流转后_status与标签同步变化_无残留() throws Exception {
        JSONObject paid = firstRow(po("PAID"));
        assertEquals("PAID", paid.getStr("status"), "流转前 status=PAID");
        assertTrue(paid.getJSONArray("tags").contains("待发货"), "流转前应带「待发货」标签");

        JSONObject shipped = firstRow(po("SHIPPED"));
        assertEquals("SHIPPED", shipped.getStr("status"), "流转后 status=SHIPPED");
        assertTrue(shipped.getJSONArray("tags").contains("待收货"), "流转后应带「待收货」标签");
        assertFalse(shipped.getJSONArray("tags").contains("待发货"),
                "流转后不得残留「待发货」标签");
    }

    // ============ 4. 非法输入：未知枚举值原样回传，不中文化不置 null ============

    @Test
    void 未知订单状态_原样回传不中文化不为null() throws Exception {
        JSONObject row = firstRow(po("UNKNOWN_STATUS"));

        assertEquals("UNKNOWN_STATUS", row.getStr("status"),
                "未知状态必须原样回传（前端可显示兜底），不得置 null 或替换为中文");
        assertTrue(row.getJSONArray("tags").isEmpty(),
                "未知状态不派生任何状态类标签，实际：" + row.getJSONArray("tags"));
    }

    @Test
    void 未知退款状态_原样回传且不派生退款标签() throws Exception {
        JSONObject row = firstRow(po("PAID"), refund(1, 1, "REFUNDING"));

        assertEquals("REFUNDING", row.getStr("refundStatus"),
                "未知退款状态必须原样回传，不得置 null");
        assertFalse(row.getJSONArray("tags").contains("退款申请中"),
                "未知退款状态不得派生「退款申请中」标签");
    }

    @Test
    void 空列表_不含任何伪造行且total为0() throws Exception {
        when(orderMapper.selectPage(any(), anyInt(), anyInt())).thenReturn(Collections.emptyList());
        when(orderMapper.countByFilter(any())).thenReturn(0);

        MvcResult result = mockMvc.perform(get("/order/page")).andReturn();
        JSONObject data = JSONUtil.parseObj(result.getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).getJSONObject("data");

        assertEquals(0, data.getJSONArray("list").size(), "空结果不得伪造行");
        assertEquals(0, data.getInt("total").intValue(), "空结果 total 必须为 0");
        List<String> keys = Arrays.asList(data.keySet().toArray(new String[0]));
        assertEquals(2, keys.size(), "空结果 data 只允许 list/total，实际：" + keys);
    }
}
