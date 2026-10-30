package com.ysh.order.service;

import com.ysh.order.dao.OrderLogMapper;
import com.ysh.order.dao.OrderMapper;
import com.ysh.order.dao.po.OrderLogPO;
import com.ysh.order.dao.po.OrderPO;
import com.ysh.order.mq.OrderEventPublisher;
import com.ysh.order.service.impl.AlipayStrategyService;
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
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Service 层取消订单流转的表格驱动测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 3.2：
 * - 3.2.1 cancel(orderId, operatorId)：PENDING -> FAILED，写 tb_order_log
 *         （event=CANCEL，from=PENDING，to=FAILED，含操作人）
 *   （Fail 边界：状态变了日志缺失 -> 订单流转审计断链）
 * - 对 FAILED 订单重复取消 -> 抛异常（终态幂等防护）
 * - 已付款及之后的状态不可取消（须走退款）
 *
 * 用例分类：
 * 1. 字段级校验：日志行六字段齐全（orderId/from/to/event/operatorId/remark）
 * 2. 类型与格式：UPDATE 落库状态串、事件名、from/to 均为枚举名精确值
 * 3. 重复创建：同一订单重复取消 -> 第二次抛异常且不追加日志、不二次 UPDATE
 * 4. 非法输入：订单不存在、非待付款状态取消 -> 抛异常且 DB 零改动、零日志
 */
class OrderCancelServiceTest {

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
        ReflectionTestUtils.setField(orderService, "payStrategies",
                List.of(new AlipayStrategyService()));
        ReflectionTestUtils.setField(orderService, "orderEventPublisher", orderEventPublisher);
    }

    private OrderPO order(int id, String status) {
        OrderPO po = new OrderPO();
        po.setId(id);
        po.setOrderNo("NO-" + id);
        po.setShopId(1);
        po.setSkuId(10);
        po.setSkuName("测试商品");
        po.setQuantity(2);
        po.setTotalPrice(new BigDecimal("99.00"));
        po.setStatus(status);
        po.setReceiverName("张三");
        po.setReceiverPhone("13800005678");
        po.setReceiverAddress("上海市浦东新区");
        po.setBuyerUsername("buyer_a");
        return po;
    }

    private OrderLogPO captureLog() {
        ArgumentCaptor<OrderLogPO> captor = ArgumentCaptor.forClass(OrderLogPO.class);
        verify(orderLogMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ============ 1/2. 字段级校验 + 类型与格式：待付款取消成功 ============

    @Test
    void 待付款取消_状态落库为FAILED() {
        when(orderMapper.selectById(100)).thenReturn(order(100, "PENDING"));

        orderService.cancel(100, OPERATOR_ID);

        verify(orderMapper).updateStatus(100, "FAILED", null);
    }

    @Test
    void 待付款取消_写CANCEL审计日志() {
        when(orderMapper.selectById(100)).thenReturn(order(100, "PENDING"));

        orderService.cancel(100, OPERATOR_ID);

        OrderLogPO logEntry = captureLog();
        assertEquals(100, logEntry.getOrderId(), "日志 orderId 不正确");
        assertEquals("PENDING", logEntry.getFromStatus(), "日志 fromStatus 必须精确为枚举名 PENDING");
        assertEquals("FAILED", logEntry.getToStatus(), "日志 toStatus 必须精确为枚举名 FAILED");
        assertEquals("CANCEL", logEntry.getEvent(), "事件名必须为 CANCEL");
        assertEquals(OPERATOR_ID, logEntry.getOperatorId(), "日志必须记录操作人");
        assertNotNull(logEntry.getRemark(), "日志备注不得为 null");
        assertFalse(logEntry.getRemark().trim().isEmpty(), "日志备注不得为空");
    }

    // ============ 3. 重复创建：终态幂等防护 ============

    @Test
    void 交易失败订单重复取消_抛异常且不重复落库() {
        when(orderMapper.selectById(100)).thenReturn(order(100, "FAILED"));

        assertThrows(IllegalStateException.class, () -> orderService.cancel(100, OPERATOR_ID),
                "FAILED 为终态，重复取消必须抛 IllegalStateException");

        verify(orderMapper, never()).updateStatus(any(), anyString(), any());
        verifyNoInteractions(orderLogMapper);
    }

    @Test
    void 连续取消同一订单_第二次拒绝且日志仅一条() {
        when(orderMapper.selectById(100)).thenReturn(order(100, "PENDING"), order(100, "FAILED"));

        orderService.cancel(100, OPERATOR_ID);
        assertThrows(IllegalStateException.class, () -> orderService.cancel(100, OPERATOR_ID),
                "第二次取消必须被拒绝");

        ArgumentCaptor<OrderLogPO> captor = ArgumentCaptor.forClass(OrderLogPO.class);
        verify(orderLogMapper).insert(captor.capture());
        assertEquals("PENDING", captor.getValue().getFromStatus(), "唯一一条日志应来自首次取消");
    }

    // ============ 4. 非法输入：非待付款状态 / 订单不存在 ============

    /**
     * 列：用例名、当前状态
     */
    static Stream<Arguments> notCancellableCases() {
        return Stream.of(
                arguments("已付款不可取消（须走退款）", "PAID"),
                arguments("已发货不可取消（须走退款）", "SHIPPED"),
                arguments("已完成不可取消", "COMPLETED"),
                arguments("待成团不可取消", "GROUP_PENDING")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notCancellableCases")
    void 非待付款状态取消_抛异常且DB零改动(String caseName, String status) {
        when(orderMapper.selectById(200)).thenReturn(order(200, status));

        assertThrows(IllegalStateException.class, () -> orderService.cancel(200, OPERATOR_ID),
                caseName + "：应抛 IllegalStateException");

        verify(orderMapper, never()).updateStatus(any(), anyString(), any());
        verifyNoInteractions(orderLogMapper);
    }

    @Test
    void 订单不存在_抛异常且不写日志() {
        when(orderMapper.selectById(99999)).thenReturn(null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> orderService.cancel(99999, OPERATOR_ID), "订单不存在应抛 IllegalArgumentException");
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("订单不存在"),
                "异常消息应提示订单不存在");

        verify(orderMapper, never()).updateStatus(any(), anyString(), any());
        verifyNoInteractions(orderLogMapper);
    }
}
