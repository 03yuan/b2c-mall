package com.ysh.order.service;

import com.ysh.order.dao.OrderLogMapper;
import com.ysh.order.dao.OrderMapper;
import com.ysh.order.dao.po.OrderLogPO;
import com.ysh.order.dao.po.OrderPO;
import com.ysh.order.enums.PayTypeEnum;
import com.ysh.order.mq.OrderEventPublisher;
import com.ysh.order.service.impl.BalanceStrategyService;
import com.ysh.order.service.impl.OrderServiceImpl;
import com.ysh.order.statemachine.OrderStateFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 余额支付（BALANCE）策略扩展的表格驱动测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 4.1（Service 层 - 策略扩展）：
 * - 4.1.1 PayTypeEnum 新增 BALANCE；新增 BalanceStrategyService（模拟实现，getPayType=BALANCE）
 *          pay(orderId, "BALANCE") -> PENDING 流转为 PAID、pay_type 落库为 BALANCE
 *          payType 非法 -> 400 级错误「不支持的支付方式」
 *   （Fail 边界：策略 Bean 未注册 -> 余额支付报「不支持的支付方式」）
 * - 4.1.2 BALANCE 支付成功仍发布 PAY_SUCCESS 事件（销量回写链路不受影响）
 *   （Fail 边界：新策略绕过事件发布 -> 商品销量不回写）
 *
 * 用例分类：
 * 1. 字段级校验：状态流转目标 PAID、pay_type 落库值、审计日志字段
 * 2. 类型与格式：策略标识为 BALANCE、支付方式字符串大小写/空格容错、事件载荷
 * 3. 重复创建：已付款订单重复支付被拒绝且不重复落库、不重复发事件
 * 4. 非法输入：非法/空/未知支付方式与策略未注册 -> 抛异常且 DB 零改动、零事件
 *
 * 说明：pay_time 是否真正写库属于 DAO 层契约，见 OrderPayTimeTest（真实 SQLite）。
 */
class OrderBalancePayTest {

    private static final int ORDER_ID = 100;
    private static final int OPERATOR_ID = 7;

    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderLogMapper orderLogMapper = mock(OrderLogMapper.class);
    private final OrderEventPublisher orderEventPublisher = mock(OrderEventPublisher.class);

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl();
        ReflectionTestUtils.setField(orderService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(orderService, "orderLogMapper", orderLogMapper);
        ReflectionTestUtils.setField(orderService, "stateFactory", new OrderStateFactory());
        ReflectionTestUtils.setField(orderService, "requestHolder",
                new com.ysh.order.component.RequestHolderComponent());
        ReflectionTestUtils.setField(orderService, "payStrategies", List.of(new BalanceStrategyService()));
        ReflectionTestUtils.setField(orderService, "orderEventPublisher", orderEventPublisher);
    }

    private OrderPO order(int id, String status) {
        OrderPO po = new OrderPO();
        po.setId(id);
        po.setOrderNo("NO-" + id);
        po.setShopId(1);
        po.setSkuId(10);
        po.setSkuName("测试商品");
        po.setQuantity(3);
        po.setTotalPrice(new BigDecimal("99.00"));
        po.setStatus(status);
        po.setReceiverName("张三");
        po.setReceiverPhone("13800005678");
        po.setReceiverAddress("上海市浦东新区");
        po.setBuyerUsername("buyer_a");
        return po;
    }

    // ============ 1. 字段级校验：余额支付成功后的落库与日志 ============

    @Test
    void 余额支付_状态流转为PAID且支付方式落库BALANCE() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(ORDER_ID, "PENDING"));

        orderService.pay(ORDER_ID, "BALANCE", OPERATOR_ID);

        verify(orderMapper).updateStatus(ORDER_ID, "PAID", "BALANCE");
    }

    @Test
    void 余额支付_写PAY审计日志且from与to为枚举名() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(ORDER_ID, "PENDING"));

        orderService.pay(ORDER_ID, "BALANCE", OPERATOR_ID);

        ArgumentCaptor<OrderLogPO> captor = ArgumentCaptor.forClass(OrderLogPO.class);
        verify(orderLogMapper).insert(captor.capture());
        OrderLogPO logEntry = captor.getValue();
        assertEquals(ORDER_ID, logEntry.getOrderId(), "日志 orderId 不正确");
        assertEquals("PENDING", logEntry.getFromStatus(), "日志 fromStatus 必须精确为枚举名 PENDING");
        assertEquals("PAID", logEntry.getToStatus(), "日志 toStatus 必须精确为枚举名 PAID");
        assertEquals("PAY", logEntry.getEvent(), "事件名必须为 PAY");
        assertEquals(OPERATOR_ID, logEntry.getOperatorId(), "日志必须记录操作人");
        assertNotNull(logEntry.getRemark(), "日志备注不得为 null");
    }

    // ============ 2. 类型与格式：策略标识与支付方式字符串容错 ============

    @Test
    void 余额策略的支付方式标识为BALANCE() {
        assertEquals("BALANCE", new BalanceStrategyService().getPayType(), "策略标识必须为 BALANCE");
    }

    /**
     * 列：用例名、支付方式字符串、期望枚举名
     */
    static Stream<Arguments> payTypeFormatCases() {
        return Stream.of(
                arguments("标准大写", "BALANCE", "BALANCE"),
                arguments("全小写", "balance", "BALANCE"),
                arguments("前后空格", "  BALANCE  ", "BALANCE"),
                arguments("混合大小写", "BalAnce", "BALANCE")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("payTypeFormatCases")
    void 支付方式字符串解析_容错大小写与空格(String caseName, String raw, String expected) {
        assertEquals(expected, PayTypeEnum.fromString(raw).name(), caseName + "：解析结果不正确");
    }

    // ============ 2. 类型与格式：4.1.2 PAY_SUCCESS 事件载荷 ============

    @Test
    void 余额支付成功_发布PAY_SUCCESS事件且载荷含skuId与quantity() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(ORDER_ID, "PENDING"));

        orderService.pay(ORDER_ID, "BALANCE", OPERATOR_ID);

        ArgumentCaptor<OrderPO> captor = ArgumentCaptor.forClass(OrderPO.class);
        verify(orderEventPublisher).publishPaySuccess(captor.capture());
        OrderPO published = captor.getValue();
        assertEquals(10, published.getSkuId(), "事件载荷 skuId 不正确（销量回写依赖该字段）");
        assertEquals(3, published.getQuantity(), "事件载荷 quantity 不正确（销量回写依赖该字段）");
    }

    // ============ 3. 重复创建：已付款订单不可重复支付 ============

    @Test
    void 已付款订单再次余额支付_抛异常且不重复落库不重复发事件() {
        when(orderMapper.selectById(ORDER_ID))
                .thenReturn(order(ORDER_ID, "PENDING"), order(ORDER_ID, "PAID"));

        orderService.pay(ORDER_ID, "BALANCE", OPERATOR_ID);
        assertThrows(IllegalStateException.class,
                () -> orderService.pay(ORDER_ID, "BALANCE", OPERATOR_ID),
                "已付款订单重复支付必须抛 IllegalStateException");

        verify(orderMapper, times(1)).updateStatus(any(), anyString(), anyString());
        verify(orderEventPublisher, times(1)).publishPaySuccess(any());
    }

    // ============ 4. 非法输入：非法支付方式 ============

    /**
     * 列：用例名、非法支付方式
     */
    static Stream<Arguments> invalidPayTypeCases() {
        return Stream.of(
                arguments("未支持的支付方式", "CASH"),
                arguments("未知支付方式", "UNKNOWN"),
                arguments("空字符串", ""),
                arguments("纯空格", "   "),
                arguments("null", null)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPayTypeCases")
    void 非法支付方式_抛异常且DB零改动零事件(String caseName, String payType) {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(ORDER_ID, "PENDING"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> orderService.pay(ORDER_ID, payType, OPERATOR_ID),
                caseName + "：应抛 IllegalArgumentException");
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("支付方式"),
                caseName + "：异常消息应提示支付方式");

        verify(orderMapper, never()).updateStatus(any(), anyString(), any());
        verifyNoInteractions(orderLogMapper);
        verify(orderEventPublisher, never()).publishPaySuccess(any());
    }

    @Test
    void 策略未注册_报不支持的支付方式且DB零改动() {
        ReflectionTestUtils.setField(orderService, "payStrategies", Collections.emptyList());
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(ORDER_ID, "PENDING"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> orderService.pay(ORDER_ID, "BALANCE", OPERATOR_ID),
                "策略未注册时应抛 IllegalArgumentException");
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("不支持的支付方式"),
                "异常消息应为「不支持的支付方式」");

        verify(orderMapper, never()).updateStatus(any(), anyString(), any());
        verifyNoInteractions(orderLogMapper);
    }
}
