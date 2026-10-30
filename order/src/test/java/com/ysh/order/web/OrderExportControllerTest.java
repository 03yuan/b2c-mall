package com.ysh.order.web;

import com.alibaba.excel.EasyExcel;
import com.ysh.order.bean.CurrentEmployeeBean;
import com.ysh.order.component.RequestHolderComponent;
import com.ysh.order.dao.po.OrderPO;
import com.ysh.order.dao.po.OrderQueryPO;
import com.ysh.order.service.OrderService;
import com.ysh.order.web.advice.GlobalExceptionHandler;
import com.ysh.order.web.excel.OrderExcelColumns;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 订单导出 GET /order/export 的契约测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 8.2（web 层 - Excel 下载）：
 * - 8.2.1 引入 EasyExcel + OrderExcelColumns 列定义常量类作为导出表头单一来源
 * - 8.2.2 响应头 Content-Type=xlsx、Content-Disposition=attachment 且文件名含时间戳（UTF-8 编码）；
 *   空结果导出仅表头的有效 xlsx；手机号导出口径=明文（与列表脱敏差异显式固化）
 *   （Fail 边界：空结果 500；导出被误脱敏 -> 商家无法联系收件人；表头两处硬编码 -> 改列漂移）
 *
 * 用例分类（表格驱动，适配导出契约）：
 * 1. 字段级校验：表头逐列与 OrderExcelColumns.HEADERS 一致；数据行与 PO 字段映射一致（手机号明文）
 * 2. 类型与格式：Content-Type xlsx、Content-Disposition attachment 且文件名含时间戳；筛选参数透传
 * 3. 重复创建：同条件重复导出数据行完全一致
 * 4. 非法输入：越权 shopId 被忽略；空结果导出合法 xlsx 仅表头
 *
 * standalone MockMvc + Mockito mock OrderService；响应字节用 EasyExcel 读回断言。
 */
class OrderExportControllerTest {

    private static final Integer LOGIN_SHOP_ID = 5;

    private OrderService orderService;
    private RequestHolderComponent requestHolder;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        requestHolder = mock(RequestHolderComponent.class);

        CurrentEmployeeBean current = new CurrentEmployeeBean();
        current.setEmployeeId(7);
        current.setShopId(LOGIN_SHOP_ID);
        current.setUsername("tom");
        when(requestHolder.get()).thenReturn(current);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new OrderExportController(orderService, requestHolder))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** 构造一条订单（明文手机号） */
    private OrderPO po(int id, String orderNo, String status, String receiverPhone) {
        OrderPO po = new OrderPO();
        po.setId(id);
        po.setOrderNo(orderNo);
        po.setShopId(LOGIN_SHOP_ID);
        po.setOrderType("NORMAL");
        po.setStatus(status);
        po.setReceiverName("张三");
        po.setReceiverPhone(receiverPhone);
        po.setPayType("WECHAT");
        po.setLogisticsType("EXPRESS");
        po.setLogisticsNo("SF001");
        po.setStoreName("一号门店");
        po.setSkuName("测试商品");
        po.setUnitPrice(new BigDecimal("19.90"));
        po.setQuantity(2);
        po.setTotalPrice(new BigDecimal("39.80"));
        po.setCreateTime(LocalDateTime.of(2026, 10, 20, 10, 0, 0));
        return po;
    }

    /** 读回 Excel 全部行（headRowNumber=0，首行表头也作为一行 Map<列索引, 文本>） */
    private List<Map<Integer, String>> readAllRows(byte[] bytes) {
        return EasyExcel.read(new ByteArrayInputStream(bytes)).headRowNumber(0).sheet(0).doReadSync();
    }

    private List<String> rowValues(Map<Integer, String> row) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < OrderExcelColumns.HEADERS.size(); i++) {
            values.add(row.get(i));
        }
        return values;
    }

    // ============ 1. 字段级校验：表头逐列一致 + 数据行手机号明文 ============

    @Test
    void 导出_表头与列定义一致_数据行手机号明文() throws Exception {
        when(orderService.exportList(any())).thenReturn(Collections.singletonList(
                po(1, "NO-1", "PAID", "13800005678")));

        MvcResult result = mockMvc.perform(get("/order/export")).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(200, response.getStatus(), "导出应返回 200");
        byte[] bytes = response.getContentAsByteArray();
        assertTrue(bytes.length > 0, "导出应返回非空 xlsx 文件");

        List<Map<Integer, String>> rows = readAllRows(bytes);
        assertEquals(2, rows.size(), "应为 1 行表头 + 1 行数据");

        List<String> header = rowValues(rows.get(0));
        assertEquals(OrderExcelColumns.HEADERS, header, "表头必须与单一列定义逐列一致");

        List<String> firstDataRow = rowValues(rows.get(1));
        List<String> expected = Arrays.asList("NO-1", "2026-10-20 10:00:00", "NORMAL", "PAID",
                "张三", "13800005678", "WECHAT", "EXPRESS", "SF001", "一号门店",
                "测试商品", "19.90", "2", "39.80");
        assertEquals(expected, firstDataRow, "数据行各列必须与 PO 字段映射一致");
        assertEquals("13800005678", firstDataRow.get(5),
                "联系电话必须为明文 11 位（商家履约需要），不得脱敏");
    }

    // ============ 2. 类型与格式：xlsx 响应头契约 ============

    @Test
    void 导出_响应头为xlsx下载流_文件名含时间戳() throws Exception {
        when(orderService.exportList(any())).thenReturn(Collections.emptyList());

        MvcResult result = mockMvc.perform(get("/order/export")).andReturn();
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

    @Test
    void 筛选参数status透传_shopId取登录上下文() throws Exception {
        when(orderService.exportList(any())).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/order/export").param("status", "PAID"));

        ArgumentCaptor<OrderQueryPO> captor = ArgumentCaptor.forClass(OrderQueryPO.class);
        verify(orderService).exportList(captor.capture());
        OrderQueryPO filter = captor.getValue();
        assertEquals(LOGIN_SHOP_ID, filter.getShopId(), "shopId 必须取登录上下文");
        assertEquals("PAID", filter.getStatus(), "status 筛选应透传");
    }

    // ============ 3. 重复创建：同条件重复导出数据行完全一致 ============

    @Test
    void 同条件重复导出_数据行完全一致() throws Exception {
        when(orderService.exportList(any())).thenReturn(Collections.singletonList(
                po(1, "NO-1", "PAID", "13800005678")));

        byte[] first = mockMvc.perform(get("/order/export")).andReturn().getResponse().getContentAsByteArray();
        byte[] second = mockMvc.perform(get("/order/export")).andReturn().getResponse().getContentAsByteArray();

        List<List<String>> firstRows = readAllRows(first).stream().map(this::rowValues).collect(Collectors.toList());
        List<List<String>> secondRows = readAllRows(second).stream().map(this::rowValues).collect(Collectors.toList());
        assertEquals(firstRows, secondRows, "重复导出的表头与数据行必须完全一致");
    }

    // ============ 4. 非法输入：越权 shopId 被忽略；空结果仅表头 ============

    @Test
    void 越权shopId参数被忽略_导出用登录shopId() throws Exception {
        when(orderService.exportList(any())).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/order/export").param("shopId", "99"));

        ArgumentCaptor<OrderQueryPO> captor = ArgumentCaptor.forClass(OrderQueryPO.class);
        verify(orderService).exportList(captor.capture());
        assertEquals(LOGIN_SHOP_ID, captor.getValue().getShopId(), "越权 shopId 参数必须被忽略");
    }

    @Test
    void 空结果_导出合法xlsx仅表头不伪造数据行() throws Exception {
        when(orderService.exportList(any())).thenReturn(Collections.emptyList());

        MvcResult result = mockMvc.perform(get("/order/export")).andReturn();
        byte[] bytes = result.getResponse().getContentAsByteArray();

        assertTrue(bytes.length > 0, "空结果也必须导出合法的 xlsx 文件");
        List<Map<Integer, String>> rows = readAllRows(bytes);
        assertEquals(1, rows.size(), "空结果应只有 1 行表头，不得伪造数据行");
        assertEquals(OrderExcelColumns.HEADERS, rowValues(rows.get(0)), "表头必须与单一列定义一致");
    }
}
