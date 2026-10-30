package com.ysh.order.web;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ysh.order.bean.CurrentEmployeeBean;
import com.ysh.order.component.RequestHolderComponent;
import com.ysh.order.dao.po.OrderPageResult;
import com.ysh.order.dao.po.OrderQueryPO;
import com.ysh.order.service.OrderService;
import com.ysh.order.web.advice.GlobalExceptionHandler;
import com.ysh.order.web.vo.OrderRowVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.stream.Stream;

import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * GET /order/page 的契约测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 5.2（Service/Controller 层）：
 * - 5.2.1 Controller 参数校验：非法 page/size -> 400；startDate > endDate -> 400
 *   「开始日期不能晚于结束日期」；筛选条件透传且 shopId 强制取自登录上下文
 *   （Fail 边界：start>end 静默查空 -> 商家误判无单；信任前端 shopId -> 水平越权）
 * - 5.2.2 空结果返回空列表 + total=0，任何字段不伪造数值
 *   （Fail 边界：空结果填充 0/示例数据）
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：11 维筛选 + page/size 全部透传 Service；list 与 total 原样返回
 * 2. 类型与格式：未传分页参数取默认 1/10；日期区间原样透传不改写时间部分
 * 3. 重复创建：同参数重复请求响应完全一致
 * 4. 非法输入：非法分页参数/非数字 400；开始日期晚于结束日期 400 且 Service 零调用；
 *    越权 shopId 参数被忽略
 *
 * standalone MockMvc + Mockito mock OrderService + 真实 GlobalExceptionHandler。
 * 红阶段 OrderQueryController 为空实现（返回 data=null），以下用例预期全部失败。
 */
class OrderQueryControllerTest {

    private static final Integer LOGIN_SHOP_ID = 3;

    private OrderService orderService;
    private RequestHolderComponent requestHolder;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        requestHolder = mock(RequestHolderComponent.class);
        when(requestHolder.get()).thenReturn(loginBean(LOGIN_SHOP_ID));
        mockMvc = MockMvcBuilders.standaloneSetup(new OrderQueryController(orderService, requestHolder))
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

    private OrderRowVO sampleOrder(int id, String orderNo, String status) {
        OrderRowVO vo = new OrderRowVO();
        vo.setOrderId(id);
        vo.setOrderNo(orderNo);
        vo.setStatus(status);
        vo.setTotalPrice(new BigDecimal("39.80"));
        return vo;
    }

    /** 响应契约：BaseResponseVO JSON 且含 data（空实现返回 data=null 时此断言即失败） */
    private JSONObject parseData(MockHttpServletResponse response) throws Exception {
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body != null && body.contains("\"data\""),
                "响应应为 BaseResponseVO JSON（含 data），实际：" + body);
        JSONObject data = JSONUtil.parseObj(body).getJSONObject("data");
        assertNotNull(data, "响应 data 不得为 null（空结果也必须返回 list=[] 且 total=0），实际：" + body);
        return data;
    }

    private MockHttpServletRequestBuilder fullFilterRequest() {
        return get("/order/page")
                .param("orderNo", "NO-C3")
                .param("orderType", "SECKILL")
                .param("orderCategory", "CARD")
                .param("status", "PAID")
                .param("receiverName", "张")
                .param("receiverPhone", "1380000")
                .param("payType", "WECHAT")
                .param("refundStatus", "PROCESSING")
                .param("logisticsType", "EXPRESS")
                .param("storeName", "一号门店")
                .param("startDate", "2026-10-01")
                .param("endDate", "2026-10-27")
                .param("page", "2")
                .param("size", "20");
    }

    // ============ 1. 字段级校验：11 维筛选 + 分页全部透传 ============

    @Test
    void 全部筛选条件透传_且shopId取自登录上下文() throws Exception {
        when(orderService.pageOrders(any())).thenReturn(new OrderPageResult(Collections.emptyList(), 0));

        mockMvc.perform(fullFilterRequest()).andReturn();

        ArgumentCaptor<OrderQueryPO> captor = ArgumentCaptor.forClass(OrderQueryPO.class);
        verify(orderService, times(1)).pageOrders(captor.capture());
        OrderQueryPO filter = captor.getValue();

        assertEquals("NO-C3", filter.getOrderNo(), "orderNo 必须透传");
        assertEquals("SECKILL", filter.getOrderType(), "orderType 必须透传");
        assertEquals("CARD", filter.getOrderCategory(), "orderCategory 必须透传");
        assertEquals("PAID", filter.getStatus(), "status 必须透传");
        assertEquals("张", filter.getReceiverName(), "receiverName 必须透传");
        assertEquals("1380000", filter.getReceiverPhone(), "receiverPhone 必须透传");
        assertEquals("WECHAT", filter.getPayType(), "payType 必须透传");
        assertEquals("PROCESSING", filter.getRefundStatus(), "refundStatus 必须透传");
        assertEquals("EXPRESS", filter.getLogisticsType(), "logisticsType 必须透传");
        assertEquals("一号门店", filter.getStoreName(), "storeName 必须透传");
        assertEquals("2026-10-01", filter.getStartDate(), "startDate 必须透传");
        assertEquals("2026-10-27", filter.getEndDate(), "endDate 必须透传");
        assertEquals(Integer.valueOf(2), filter.getPage(), "page 必须透传");
        assertEquals(Integer.valueOf(20), filter.getSize(), "size 必须透传");
        assertEquals(LOGIN_SHOP_ID, filter.getShopId(), "shopId 必须取登录上下文");
    }

    @Test
    void 非空结果_list条数与total原样返回_total非本页条数() throws Exception {
        when(orderService.pageOrders(any())).thenReturn(new OrderPageResult(
                Arrays.asList(sampleOrder(9, "NO-I9", "SHIPPED"), sampleOrder(8, "NO-H8", "PAID")), 5));

        MvcResult result = mockMvc.perform(get("/order/page")).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(200, response.getStatus(), "查询成功应返回 200");
        JSONObject data = parseData(response);
        assertEquals(5, data.getInt("total").intValue(), "total 应为条件命中总数而非本页条数");
        JSONArray list = data.getJSONArray("list");
        assertEquals(2, list.size(), "当前页条数应与 Service 返回一致");
        assertEquals("NO-I9", list.getJSONObject(0).getStr("orderNo"), "行数据必须真实返回");
    }

    // ============ 4. 非法输入：空结果 -> 空列表 + total=0，不伪造任何字段 ============

    @Test
    void 空结果_返回空列表与total为0_不伪造任何字段() throws Exception {
        when(orderService.pageOrders(any())).thenReturn(new OrderPageResult(Collections.emptyList(), 0));

        MvcResult result = mockMvc.perform(get("/order/page")).andReturn();
        JSONObject data = parseData(result.getResponse());

        assertEquals(0, data.getInt("total").intValue(), "空结果 total 必须为 0");
        assertEquals(0, data.getJSONArray("list").size(), "空结果必须为空列表（前端渲染「暂无数据」）");
        assertEquals(new HashSet<>(Arrays.asList("list", "total")), data.keySet(),
                "空结果时 data 只允许 list/total，不得伪造任何行字段或数值");
    }

    // ============ 2. 类型与格式：默认分页、日期原样 ============

    @Test
    void 未传分页参数_取默认页码1与每页10() throws Exception {
        when(orderService.pageOrders(any())).thenReturn(new OrderPageResult(Collections.emptyList(), 0));

        mockMvc.perform(get("/order/page")).andReturn();

        ArgumentCaptor<OrderQueryPO> captor = ArgumentCaptor.forClass(OrderQueryPO.class);
        verify(orderService).pageOrders(captor.capture());
        assertEquals(Integer.valueOf(1), captor.getValue().getPage(), "page 默认值应为 1");
        assertEquals(Integer.valueOf(10), captor.getValue().getSize(), "size 默认值应为 10");
    }

    @Test
    void 日期区间原样透传_不改写时间部分() throws Exception {
        when(orderService.pageOrders(any())).thenReturn(new OrderPageResult(Collections.emptyList(), 0));

        mockMvc.perform(get("/order/page")
                .param("startDate", "2026-10-22")
                .param("endDate", "2026-10-22")).andReturn();

        ArgumentCaptor<OrderQueryPO> captor = ArgumentCaptor.forClass(OrderQueryPO.class);
        verify(orderService).pageOrders(captor.capture());
        assertEquals("2026-10-22", captor.getValue().getStartDate(),
                "开始日期必须原样透传（00:00:00 的补齐是 DAO 职责）");
        assertEquals("2026-10-22", captor.getValue().getEndDate(),
                "结束日期必须原样透传（23:59:59 的补齐是 DAO 职责）");
    }

    // ============ 1. 字段级校验：shopId 强制取自登录上下文（越权参数忽略） ============

    @Test
    void 越权shopId参数被忽略_仍以登录shopId查询() throws Exception {
        when(orderService.pageOrders(any())).thenReturn(new OrderPageResult(Collections.emptyList(), 0));

        mockMvc.perform(get("/order/page")
                .param("shopId", "99")
                .param("status", "PAID")).andReturn();

        ArgumentCaptor<OrderQueryPO> captor = ArgumentCaptor.forClass(OrderQueryPO.class);
        verify(orderService).pageOrders(captor.capture());
        assertEquals(LOGIN_SHOP_ID, captor.getValue().getShopId(),
                "shopId 必须恒取登录上下文，请求参数 shopId 一律忽略");
        assertEquals("PAID", captor.getValue().getStatus(), "越权参数被忽略时筛选必须照常透传");
    }

    // ============ 4. 非法输入：非法分页参数一律 400 中文消息且不查库 ============

    /**
     * 列：用例名、参数名、参数值、期望中文消息片段
     */
    static Stream<Arguments> illegalPagingCases() {
        return Stream.of(
                arguments("page=0拒绝", "page", "0", "页码"),
                arguments("page=-1拒绝", "page", "-1", "页码"),
                arguments("page非数字拒绝", "page", "abc", "分页参数格式"),
                arguments("size=0拒绝", "size", "0", "每页条数"),
                arguments("size=101拒绝", "size", "101", "每页条数"),
                arguments("size负数拒绝", "size", "-5", "每页条数"),
                arguments("size非数字拒绝", "size", "abc", "分页参数格式")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("illegalPagingCases")
    void 非法分页参数_返回400中文消息且不查库(String caseName, String param, String value,
                                            String messageFragment) throws Exception {
        MvcResult result = mockMvc.perform(get("/order/page").param(param, value)).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(400, response.getStatus(),
                caseName + "：非法分页参数必须拒绝为 400，实际：" + response.getStatus());
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains(messageFragment),
                caseName + "：400 响应应含中文原因「" + messageFragment + "」，实际：" + body);
        verifyNoInteractions(orderService);
    }

    // ============ 4. 非法输入：开始日期晚于结束日期 ============

    @Test
    void 开始日期晚于结束日期_返回400且不查库() throws Exception {
        MvcResult result = mockMvc.perform(get("/order/page")
                .param("startDate", "2026-10-27")
                .param("endDate", "2026-10-01")).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(400, response.getStatus(), "start > end 必须报 400 而非静默查空");
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("开始日期不能晚于结束日期"),
                "400 响应应明确提示「开始日期不能晚于结束日期」，实际：" + body);
        verifyNoInteractions(orderService);
    }

    @Test
    void 开始日期等于结束日期_合法并可查询() throws Exception {
        when(orderService.pageOrders(any())).thenReturn(new OrderPageResult(Collections.emptyList(), 0));

        MvcResult result = mockMvc.perform(get("/order/page")
                .param("startDate", "2026-10-22")
                .param("endDate", "2026-10-22")).andReturn();

        assertEquals(200, result.getResponse().getStatus(), "start == end 属合法单日区间");
        verify(orderService, times(1)).pageOrders(any());
    }

    // ============ 3. 重复创建：同参数重复请求响应完全一致 ============

    @Test
    void 同参数重复请求_响应完全一致() throws Exception {
        when(orderService.pageOrders(any())).thenReturn(new OrderPageResult(
                Collections.singletonList(sampleOrder(3, "NO-C3", "SHIPPED")), 1));

        MvcResult first = mockMvc.perform(get("/order/page").param("status", "SHIPPED")).andReturn();
        MvcResult second = mockMvc.perform(get("/order/page").param("status", "SHIPPED")).andReturn();

        JSONObject firstData = parseData(first.getResponse());
        JSONObject secondData = parseData(second.getResponse());
        assertEquals(1, firstData.getInt("total").intValue(), "total 应为条件命中总数");
        assertEquals(firstData.toString(), secondData.toString(),
                "同参数重复请求必须返回完全一致的结果（幂等、不串态）");
        verify(orderService, times(2)).pageOrders(any());
    }
}
