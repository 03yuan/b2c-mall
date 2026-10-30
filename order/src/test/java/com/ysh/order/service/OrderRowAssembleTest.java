package com.ysh.order.service;

import com.ysh.order.dao.OrderMapper;
import com.ysh.order.dao.RefundMapper;
import com.ysh.order.dao.po.OrderPO;
import com.ysh.order.dao.po.OrderPageResult;
import com.ysh.order.dao.po.OrderQueryPO;
import com.ysh.order.dao.po.RefundPO;
import com.ysh.order.service.impl.OrderServiceImpl;
import com.ysh.order.web.vo.OrderItemVO;
import com.ysh.order.web.vo.OrderRowVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商家端订单行 VO 组装的测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 6.1 / 6.2：
 * - 6.1.1 行 VO 字段齐全：orderId、orderNo、createTime、storeName、
 *   items[{skuImage、skuName、unitPrice、quantity、isGift}]、totalPrice、totalQuantity、
 *   payType、buyerUsername、receiverName、receiverPhoneMasked、status、refundStatus
 *   （Fail 边界：漏列 -> 前端表头对不上；items 返回对象而非数组 -> 多商品扩展时前端重写）
 * - 6.1.2 商品标签派生：PAID->待发货、SHIPPED->待收货、refundStatus=PROCESSING->退款申请中、
 *   order_category=CARD->电子卡券订单、logistics_type=CITY->同城配送、is_gift=1->赠品
 *   （Fail 边界：标签硬编码不随字段变化 -> 状态流转后标签残留）
 * - 6.1.3 手机号脱敏：11 位 -> 前 3 后 4（138****5678）；非 11 位 -> 中间掩码；
 *   null/空 -> 空串（Fail 边界：列表明文手机号 -> 隐私泄漏；短号串越界抛异常）
 * - 6.1.4 售后状态批量派生：按本页 orderId IN 一次取回，禁止逐行查询（N+1）
 * - 6.2.1 VO 同时提供 status / refundStatus 原始枚举值（非仅中文文案）
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：14 个 VO 字段逐项断言、items 数组含 1 条、totalQuantity=quantity
 * 2. 类型与格式：手机号脱敏表格驱动（11 位/7 位/4 位/3 位/空串/null）、金额 BigDecimal 精度、
 *    createTime 原样、原始枚举值
 * 3. 重复创建：同页重复组装结果一致；空页返回空列表且 total=0（不伪造）
 * 4. 非法输入：无退款单 -> refundStatus=NONE 不 NPE；refundMapper 返回 null -> 按无售后处理；
 *    buyerUsername 历史单为 null -> 空串；20 行仅 1 次批量查询（N+1 防回归）
 *
 * Mockito mock OrderMapper / RefundMapper，被测物为真实 OrderServiceImpl.pageOrders。
 */
class OrderRowAssembleTest {

    private static final Integer SHOP_ID = 3;

    private OrderMapper orderMapper;
    private RefundMapper refundMapper;
    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        refundMapper = mock(RefundMapper.class);
        orderService = new OrderServiceImpl();
        ReflectionTestUtils.setField(orderService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(orderService, "refundMapper", refundMapper);
    }

    private OrderQueryPO filter() {
        OrderQueryPO filter = new OrderQueryPO();
        filter.setShopId(SHOP_ID);
        return filter;
    }

    private OrderPO po(int id, String orderNo, String status) {
        OrderPO po = new OrderPO();
        po.setId(id);
        po.setOrderNo(orderNo);
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
        po.setReceiverAddress("上海市浦东新区测试路 1 号");
        po.setStoreName("一号门店");
        po.setOrderType("NORMAL");
        po.setOrderCategory("NORMAL");
        po.setLogisticsType("EXPRESS");
        po.setIsGift(0);
        po.setBuyerDeleted(0);
        po.setCreateTime(LocalDateTime.of(2026, 10, 20, 10, 0, 0));
        return po;
    }

    private RefundPO refund(int id, int orderId, String status) {
        RefundPO refund = new RefundPO();
        refund.setId(id);
        refund.setOrderId(orderId);
        refund.setShopId(SHOP_ID);
        refund.setStatus(status);
        refund.setAmount(new BigDecimal("39.80"));
        return refund;
    }

    /** 组装一页：mock DAO 返回值后调用真实 Service */
    private List<OrderRowVO> assemble(List<OrderPO> orders) {
        when(orderMapper.selectPage(any(), anyInt(), anyInt())).thenReturn(orders);
        when(orderMapper.countByFilter(any())).thenReturn(orders.size());
        when(refundMapper.selectByOrderIds(any())).thenReturn(Collections.emptyList());
        return orderService.pageOrders(filter()).getRows();
    }

    private OrderRowVO assembleSingle(OrderPO po, RefundPO... refunds) {
        when(orderMapper.selectPage(any(), anyInt(), anyInt())).thenReturn(Collections.singletonList(po));
        when(orderMapper.countByFilter(any())).thenReturn(1);
        when(refundMapper.selectByOrderIds(any())).thenReturn(Arrays.asList(refunds));
        return orderService.pageOrders(filter()).getRows().get(0);
    }

    // ============ 1. 字段级校验：14 个 VO 字段齐全 ============

    @Test
    void 行VO字段齐全_逐项断言() {
        OrderRowVO vo = assembleSingle(po(1, "NO-A1", "PAID"));

        assertEquals(Integer.valueOf(1), vo.getOrderId(), "orderId");
        assertEquals("NO-A1", vo.getOrderNo(), "orderNo");
        assertEquals(LocalDateTime.of(2026, 10, 20, 10, 0, 0), vo.getCreateTime(), "createTime 应原样返回");
        assertEquals("一号门店", vo.getStoreName(), "storeName 多门店订单必须非空");
        assertEquals("WECHAT", vo.getPayType(), "payType");
        assertEquals("buyer_a", vo.getBuyerUsername(), "buyerUsername");
        assertEquals("张三", vo.getReceiverName(), "receiverName");
        assertEquals("138****5678", vo.getReceiverPhoneMasked(), "列表必须脱敏");
        assertEquals("PAID", vo.getStatus(), "status 原始枚举值");
        assertEquals("NONE", vo.getRefundStatus(), "无退款单时售后状态为 NONE");
        assertEquals(0, new BigDecimal("39.80").compareTo(vo.getTotalPrice()), "totalPrice 实付金额");
    }

    @Test
    void items为数组且含一条_字段齐全() {
        OrderRowVO vo = assembleSingle(po(1, "NO-A1", "PAID"));

        assertNotNull(vo.getItems(), "items 必须返回数组而非对象");
        assertEquals(1, vo.getItems().size(), "一单一商品模型下 items 恒 1 条");
        OrderItemVO item = vo.getItems().get(0);
        assertEquals("https://img.example.com/a.png", item.getSkuImage(), "skuImage");
        assertEquals("测试商品", item.getSkuName(), "skuName");
        assertEquals(0, new BigDecimal("19.90").compareTo(item.getUnitPrice()), "unitPrice 成交单价");
        assertEquals(Integer.valueOf(2), item.getQuantity(), "quantity");
        assertEquals(Integer.valueOf(0), item.getIsGift(), "isGift");
        assertEquals(Integer.valueOf(2), vo.getTotalQuantity(), "totalQuantity = Σ items.quantity");
    }

    @Test
    void totalQuantity等于各商品数量之和() {
        OrderPO po = po(1, "NO-A1", "PAID");
        po.setQuantity(5);

        OrderRowVO vo = assembleSingle(po);

        assertEquals(Integer.valueOf(5), vo.getTotalQuantity(), "共 N 件 = Σ items.quantity");
        assertEquals(Integer.valueOf(5), vo.getItems().get(0).getQuantity(), "items 内数量应与之一致");
    }

    // ============ 2. 类型与格式：手机号脱敏表格驱动 ============

    /**
     * 列：用例名、原始手机号、期望脱敏结果
     */
    static Stream<Arguments> maskCases() {
        return Stream.of(
                arguments("11位手机号前3后4", "13800005678", "138****5678"),
                arguments("11位手机号尾号2222", "13800002222", "138****2222"),
                arguments("7位短号中间掩码", "1234567", "12****67"),
                arguments("4位最短可保留首尾", "1234", "12****34"),
                arguments("3位过短全掩码", "123", "****"),
                arguments("空串原样返回空串", "", ""),
                arguments("null返回空串不抛异常", null, "")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("maskCases")
    void 手机号脱敏_边界表格驱动(String caseName, String raw, String expected) {
        OrderPO po = po(1, "NO-A1", "PAID");
        po.setReceiverPhone(raw);

        OrderRowVO vo = assembleSingle(po);

        assertEquals(expected, vo.getReceiverPhoneMasked(), caseName + "：脱敏结果不符");
    }

    // ============ 2. 类型与格式：6.2.1 原始枚举值 ============

    /**
     * 列：用例名、订单状态、退款单状态、期望 status、期望 refundStatus
     */
    static Stream<Arguments> rawEnumCases() {
        return Stream.of(
                arguments("待付款原样PENDING", "PENDING", null, "PENDING", "NONE"),
                arguments("待发货原样PAID", "PAID", null, "PAID", "NONE"),
                arguments("待收货原样SHIPPED", "SHIPPED", null, "SHIPPED", "NONE"),
                arguments("已完成原样COMPLETED", "COMPLETED", null, "COMPLETED", "NONE"),
                arguments("交易失败原样FAILED", "FAILED", null, "FAILED", "NONE"),
                arguments("待成团原样GROUP_PENDING", "GROUP_PENDING", null, "GROUP_PENDING", "NONE"),
                arguments("售后申请中原样PROCESSING", "SHIPPED", "PROCESSING", "SHIPPED", "PROCESSING"),
                arguments("售后完成原样DONE", "COMPLETED", "DONE", "COMPLETED", "DONE")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rawEnumCases")
    void 原始枚举值_非中文文案(String caseName, String status, String refundStatus,
                           String expectedStatus, String expectedRefundStatus) {
        OrderPO po = po(1, "NO-A1", status);
        RefundPO refund = refundStatus == null ? null : refund(1, 1, refundStatus);

        OrderRowVO vo = refund == null ? assembleSingle(po) : assembleSingle(po, refund);

        assertEquals(expectedStatus, vo.getStatus(), caseName + "：status 必须为原始枚举值");
        assertEquals(expectedRefundStatus, vo.getRefundStatus(), caseName + "：refundStatus 必须为原始枚举值");
    }

    // ============ 1. 字段级校验：6 类标签派生（命中与不命中各一） ============

    /**
     * 列：用例名、订单状态、退款状态、订单类别、物流类型、赠品标记、期望含该标签、期望不含该标签
     */
    static Stream<Arguments> tagCases() {
        return Stream.of(
                arguments("PAID派生待发货", "PAID", null, "NORMAL", "EXPRESS", 0, "待发货", "待收货"),
                arguments("PENDING不派生待发货", "PENDING", null, "NORMAL", "EXPRESS", 0, null, "待发货"),
                arguments("SHIPPED派生待收货", "SHIPPED", null, "NORMAL", "EXPRESS", 0, "待收货", "待发货"),
                arguments("COMPLETED不派生待收货", "COMPLETED", null, "NORMAL", "EXPRESS", 0, null, "待收货"),
                arguments("退款申请中派生退款标签", "PAID", "PROCESSING", "NORMAL", "EXPRESS", 0,
                        "退款申请中", "电子卡券订单"),
                arguments("退款完成不派生退款申请中", "COMPLETED", "DONE", "NORMAL", "EXPRESS", 0,
                        null, "退款申请中"),
                arguments("电子卡券订单派生类别标签", "PAID", null, "CARD", "EXPRESS", 0,
                        "电子卡券订单", "同城配送"),
                arguments("普通订单不派生类别标签", "PAID", null, "NORMAL", "EXPRESS", 0,
                        null, "电子卡券订单"),
                arguments("同城配送派生物流标签", "SHIPPED", null, "NORMAL", "CITY", 0,
                        "同城配送", "电子卡券订单"),
                arguments("普通快递不派生物流标签", "SHIPPED", null, "NORMAL", "EXPRESS", 0,
                        null, "同城配送"),
                arguments("赠品派生赠品标签", "PAID", null, "NORMAL", "EXPRESS", 1, "赠品", "待收货"),
                arguments("非赠品不派生赠品标签", "PAID", null, "NORMAL", "EXPRESS", 0, null, "赠品")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tagCases")
    void 标签派生_随字段变化(String caseName, String status, String refundStatus, String orderCategory,
                        String logisticsType, int isGift, String expectedTag, String absentTag) {
        OrderPO po = po(1, "NO-A1", status);
        po.setOrderCategory(orderCategory);
        po.setLogisticsType(logisticsType);
        po.setIsGift(isGift);
        RefundPO refund = refundStatus == null ? null : refund(1, 1, refundStatus);

        OrderRowVO vo = refund == null ? assembleSingle(po) : assembleSingle(po, refund);

        assertNotNull(vo.getTags(), "tags 必须返回列表（可为空列表）而非 null");
        if (expectedTag != null) {
            assertTrue(vo.getTags().contains(expectedTag),
                    caseName + "：应派生标签「" + expectedTag + "」，实际：" + vo.getTags());
        }
        if (absentTag != null) {
            assertFalse(vo.getTags().contains(absentTag),
                    caseName + "：不得残留标签「" + absentTag + "」，实际：" + vo.getTags());
        }
    }

    @Test
    void 多标签可同时派生() {
        OrderPO po = po(1, "NO-A1", "PAID");
        po.setOrderCategory("CARD");
        po.setLogisticsType("CITY");
        po.setIsGift(1);

        OrderRowVO vo = assembleSingle(po);

        assertTrue(vo.getTags().containsAll(Arrays.asList("待发货", "电子卡券订单", "同城配送", "赠品")),
                "各类标签互斥判断错误时应可同时派生，实际：" + vo.getTags());
    }

    // ============ 4. 非法输入：无退款单/批量返回 null/历史单买家为空 ============

    @Test
    void 无退款单_售后状态NONE且不抛异常() {
        OrderRowVO vo = assembleSingle(po(1, "NO-A1", "COMPLETED"));

        assertEquals("NONE", vo.getRefundStatus(), "无退款单必须为 NONE 而非 null");
    }

    @Test
    void 批量退款查询返回null_按无售后处理不抛异常() {
        when(orderMapper.selectPage(any(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(po(1, "NO-A1", "PAID")));
        when(orderMapper.countByFilter(any())).thenReturn(1);
        when(refundMapper.selectByOrderIds(any())).thenReturn(null);

        OrderRowVO vo = orderService.pageOrders(filter()).getRows().get(0);

        assertEquals("NONE", vo.getRefundStatus(), "DAO 返回 null 时应退化为无售后，不得 NPE");
        assertNotNull(vo.getTags(), "tags 不得为 null");
    }

    @Test
    void 历史单buyerUsername为null_返回空串而非NPE() {
        OrderPO po = po(1, "NO-A1", "PAID");
        po.setBuyerUsername(null);

        OrderRowVO vo = assembleSingle(po);

        assertEquals("", vo.getBuyerUsername(), "历史单未写入买家账号时应返回空串");
        assertNotNull(vo.getReceiverName(), "其他字段不受影响");
    }

    @Test
    void 同单多条退款单_取最新一条状态() {
        OrderPO po = po(1, "NO-A1", "COMPLETED");
        // DAO 按 id DESC 返回，首条即最新
        OrderRowVO vo = assembleSingle(po, refund(9, 1, "DONE"), refund(5, 1, "PROCESSING"));

        assertEquals("DONE", vo.getRefundStatus(), "同单多条退款单应取最新一条（首条）");
    }

    // ============ 4. 非法输入：售后状态批量查询防 N+1 ============

    @Test
    void 一页20行_仅发起一次批量退款查询() {
        List<OrderPO> orders = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            orders.add(po(i, "NO-" + i, "PAID"));
        }
        when(orderMapper.selectPage(any(), anyInt(), anyInt())).thenReturn(orders);
        when(orderMapper.countByFilter(any())).thenReturn(20);
        when(refundMapper.selectByOrderIds(any())).thenReturn(Collections.emptyList());

        List<OrderRowVO> rows = orderService.pageOrders(filter()).getRows();

        assertEquals(20, rows.size(), "应组装 20 行");
        ArgumentCaptor<List<Integer>> captor = ArgumentCaptor.forClass(List.class);
        verify(refundMapper, times(1)).selectByOrderIds(captor.capture());
        assertEquals(20, captor.getValue().size(), "一次批量查询应带上本页全部 orderId");
        assertTrue(captor.getValue().contains(1) && captor.getValue().contains(20),
                "批量查询参数应覆盖本页首尾 orderId");
    }

    // ============ 3. 重复创建：重复组装一致、空页不伪造 ============

    @Test
    void 同一页重复组装_结果一致() {
        OrderPO po = po(1, "NO-A1", "PAID");
        when(orderMapper.selectPage(any(), anyInt(), anyInt())).thenReturn(Collections.singletonList(po));
        when(orderMapper.countByFilter(any())).thenReturn(1);
        when(refundMapper.selectByOrderIds(any())).thenReturn(Collections.emptyList());

        OrderRowVO first = orderService.pageOrders(filter()).getRows().get(0);
        OrderRowVO second = orderService.pageOrders(filter()).getRows().get(0);

        assertEquals(first.getOrderNo(), second.getOrderNo(), "重复组装订单号应一致");
        assertEquals(first.getReceiverPhoneMasked(), second.getReceiverPhoneMasked(), "重复组装脱敏结果应一致");
        assertEquals(first.getTags(), second.getTags(), "重复组装标签应一致");
        assertEquals(first.getRefundStatus(), second.getRefundStatus(), "重复组装售后状态应一致");
    }

    @Test
    void 空页_返回空列表且total为0() {
        when(orderMapper.selectPage(any(), anyInt(), anyInt())).thenReturn(Collections.emptyList());
        when(orderMapper.countByFilter(any())).thenReturn(0);
        when(refundMapper.selectByOrderIds(any())).thenReturn(Collections.emptyList());

        OrderPageResult result = orderService.pageOrders(filter());

        assertNotNull(result.getRows(), "空页必须返回空列表而非 null");
        assertTrue(result.getRows().isEmpty(), "空页不得伪造行数据");
        assertEquals(0, result.getTotal(), "空页 total 必须为 0");
        verify(refundMapper, times(0)).selectByOrderIds(any());
    }

    @Test
    void 空页_total仍取筛选命中总数() {
        when(orderMapper.selectPage(any(), anyInt(), anyInt())).thenReturn(Collections.emptyList());
        when(orderMapper.countByFilter(any())).thenReturn(37);

        OrderPageResult result = orderService.pageOrders(filter());

        assertEquals(37, result.getTotal(), "超页时 total 仍应为筛选命中总数");
        assertTrue(result.getRows().isEmpty(), "超页行列表应为空");
    }
}
