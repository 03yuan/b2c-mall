package com.ysh.order.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ysh.order.component.RequestHolderComponent;
import com.ysh.order.dao.OrderLogMapper;
import com.ysh.order.dao.OrderMapper;
import com.ysh.order.dao.po.OrderPO;
import com.ysh.order.mq.MqProducer;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 订单事件发布（RocketMQ 事件驱动出口）测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-dashboard.md 功能上下文 5.1：
 * - 5.1.1 支付成功发布事件（topic：b2c-order-events，tag：PAY_SUCCESS），
 *   载荷含 shopId、orderId、buyerUsername、时间戳
 *   （Fail 边界：漏 shopId 则消费端无法归属店铺）
 * - 5.1.2 退款申请（REFUND_APPLY）与确认收货（CONFIRM_RECEIPT）各发布对应事件
 *   （Fail 边界：三类事件 tag 混用导致消息文本错模板）
 * MQ 用 mock producer 验证（真实 RocketMQ 端到端留给联调）。
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：载荷四字段（shopId/orderId/buyerUsername/时间戳）齐全不为空
 * 2. 类型与格式：topic/tag 字面值精确、时间戳为数字、shopId/买家名原样入载荷
 * 3. 重复创建：重复触发同一业务动作被状态机拒绝，事件只发一次（见独立测试）
 * 4. 非法输入：非法状态流转/订单不存在 -> 抛异常且零事件
 *   （带合法对照动作：对照动作发 1 条而总数仍为 1，证明非法动作未混入额外事件）
 */
class OrderEventPublishTest {

    private final MqProducer mqProducer = mock(MqProducer.class);
    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderLogMapper orderLogMapper = mock(OrderLogMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl();
        ReflectionTestUtils.setField(orderService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(orderService, "orderLogMapper", orderLogMapper);
        ReflectionTestUtils.setField(orderService, "stateFactory", new OrderStateFactory());
        ReflectionTestUtils.setField(orderService, "requestHolder", mock(RequestHolderComponent.class));
        ReflectionTestUtils.setField(orderService, "payStrategies", List.of(new AlipayStrategyService()));
        ReflectionTestUtils.setField(orderService, "orderEventPublisher", new OrderEventPublisher(mqProducer));
    }

    /** 业务动作 -> 服务调用（pay 需要支付方式，统一 ALIPAY） */
    private void doAction(String action, Integer orderId) {
        switch (action) {
            case "pay":
                orderService.pay(orderId, "ALIPAY", 1);
                break;
            case "refundApply":
                orderService.refundApply(orderId, 1);
                break;
            case "confirm":
                orderService.confirm(orderId, 1);
                break;
            default:
                throw new IllegalArgumentException("未知业务动作: " + action);
        }
    }

    /** 订单夹具 */
    private OrderPO order(int id, Integer shopId, String buyerUsername, String status) {
        OrderPO order = new OrderPO();
        order.setId(id);
        order.setOrderNo("NO" + id);
        order.setShopId(shopId);
        order.setBuyerUsername(buyerUsername);
        order.setStatus(status);
        order.setQuantity(1);
        order.setTotalPrice(new BigDecimal("99.00"));
        return order;
    }

    /**
     * 列：用例名、业务动作、订单初始状态（"-"=订单不存在）、shopId、买家名、
     *     期望 tag（"NONE"=期望抛异常且零事件）
     */
    static Stream<Arguments> eventCases() {
        return Stream.of(
                // ----1. 字段级校验：载荷四字段齐全（漏 shopId 则消费端无法归属店铺）----
                arguments("支付成功事件载荷四字段齐全", "pay", "PENDING", 7, "buyer-小王", "PAY_SUCCESS"),
                arguments("退款申请事件载荷四字段齐全", "refundApply", "PAID", 7, "buyer-小王", "REFUND_APPLY"),
                arguments("确认收货事件载荷四字段齐全", "confirm", "SHIPPED", 7, "buyer-小王", "CONFIRM_RECEIPT"),

                // ----2. 类型与格式：topic/tag 字面精确、时间戳数字、载荷值原样透传----
                arguments("大数值shopId原样入载荷", "pay", "PENDING", 4242, "buyer-9", "PAY_SUCCESS"),
                arguments("中文买家名原样入载荷", "refundApply", "SHIPPED", 3, "buyer-李四", "REFUND_APPLY"),
                arguments("时间戳为正整数数字类型", "confirm", "SHIPPED", 7, "buyer-小王", "CONFIRM_RECEIPT"),

                // ----3. 重复创建（重复触发不重复发事件）见下方独立测试----

                // ----4. 非法输入：非法流转/订单不存在 -> 抛异常且零事件----
                arguments("未付款订单确认收货被拒零事件", "confirm", "PENDING", 7, "buyer-小王", "NONE"),
                arguments("已支付订单重复支付被拒零事件", "pay", "PAID", 7, "buyer-小王", "NONE"),
                arguments("订单不存在发起退款被拒零事件", "refundApply", "-", 7, "buyer-小王", "NONE")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("eventCases")
    void 事件发布_tag与载荷契约(String caseName, String action, String orderStatus,
                              Integer shopId, String buyerUsername, String expectedTag) throws Exception {
        if ("-".equals(orderStatus)) {
            // 订单不存在：selectById 返回 null
        } else {
            when(orderMapper.selectById(11)).thenReturn(order(11, shopId, buyerUsername, orderStatus));
        }

        if ("NONE".equals(expectedTag)) {
            // 非法输入：动作被拒绝（抛异常）且不产生任何事件
            assertThrows(Exception.class, () -> doAction(action, 11));
            verifyNoInteractions(mqProducer);

            // 对照：同一环境的合法动作（待付款订单支付）应恰好发 1 条事件——
            // 既保证红阶段必失败，又证明非法动作未混入额外事件（混入则总数 > 1）
            when(orderMapper.selectById(99)).thenReturn(order(99, 7, "buyer-对照", "PENDING"));
            doAction("pay", 99);
            verify(mqProducer, times(1)).send(anyString(), anyString(), anyString());
        } else {
            doAction(action, 11);

            // topic 恒为 b2c-order-events、tag 与业务动作一一对应（only() 保证无第二条混用事件）
            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(mqProducer, only()).send(eq("b2c-order-events"), eq(expectedTag), bodyCaptor.capture());

            JsonNode payload = objectMapper.readTree(bodyCaptor.getValue());
            assertEquals(shopId, payload.get("shopId").asInt(), "漏/错 shopId 则消费端无法归属店铺");
            assertEquals(11, payload.get("orderId").asInt(), "订单号用于前端点击跳订单详情");
            assertEquals(buyerUsername, payload.get("buyerUsername").asText(), "买家名原样入载荷");
            assertTrue(payload.get("timestamp").isNumber() && payload.get("timestamp").asLong() > 0,
                    "时间戳应为正整数数字类型");
        }
    }

    // ----3. 重复创建：重复触发同一业务动作被状态机拒绝，事件只发一次（幂等、无重复消息）----

    @Test
    void 重复触发业务动作_状态机拒绝_事件只发一次() throws Exception {
        // 首次待付款、再次已支付（模拟支付成功后 DB 状态已更新）
        when(orderMapper.selectById(11))
                .thenReturn(order(11, 7, "buyer-小王", "PENDING"))
                .thenReturn(order(11, 7, "buyer-小王", "PAID"));

        orderService.pay(11, "ALIPAY", 1);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(mqProducer, times(1)).send(eq("b2c-order-events"), eq("PAY_SUCCESS"), bodyCaptor.capture());
        assertEquals(11, objectMapper.readTree(bodyCaptor.getValue()).get("orderId").asInt(),
                "重复计算前事件本身必须正确");

        assertThrows(Exception.class, () -> orderService.pay(11, "ALIPAY", 1));
        verify(mqProducer, times(1)).send(anyString(), anyString(), anyString());
    }

    // ----8.1（shop-product-list）：PAY_SUCCESS 载荷必须含 skuId/quantity，供 product 侧销量回写----

    @Test
    void 支付成功事件_载荷含skuId与quantity() throws Exception {
        OrderPO order = order(11, 7, "buyer-小王", "PENDING");
        order.setSkuId(55);
        order.setQuantity(3);
        when(orderMapper.selectById(11)).thenReturn(order);

        orderService.pay(11, "ALIPAY", 1);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(mqProducer, only()).send(eq("b2c-order-events"), eq("PAY_SUCCESS"), bodyCaptor.capture());
        JsonNode payload = objectMapper.readTree(bodyCaptor.getValue());
        assertTrue(payload.hasNonNull("skuId"), "缺 skuId 则消费端无法定位商品（销量回写失效）");
        assertEquals(55, payload.get("skuId").asInt(), "skuId 原样入载荷");
        assertTrue(payload.hasNonNull("quantity"), "缺 quantity 则消费端无法累加销量");
        assertEquals(3, payload.get("quantity").asInt(), "quantity 原样入载荷");
    }

    // ----4. 非法输入补充：三类事件 tag 一一对应、不得混用（Fail 边界：tag 混用导致消息文本错模板）----

    @Test
    void 三类事件tag一一对应_不混用() {
        when(orderMapper.selectById(1)).thenReturn(order(1, 7, "buyer-甲", "PENDING"));
        when(orderMapper.selectById(2)).thenReturn(order(2, 7, "buyer-甲", "PAID"));
        when(orderMapper.selectById(3)).thenReturn(order(3, 7, "buyer-乙", "SHIPPED"));

        orderService.pay(1, "ALIPAY", 1);
        orderService.refundApply(2, 1);
        orderService.confirm(3, 1);

        ArgumentCaptor<String> tagCaptor = ArgumentCaptor.forClass(String.class);
        verify(mqProducer, times(3)).send(eq("b2c-order-events"), tagCaptor.capture(), anyString());
        assertEquals(List.of("PAY_SUCCESS", "REFUND_APPLY", "CONFIRM_RECEIPT"), tagCaptor.getAllValues(),
                "三类事件 tag 混用会导致消费端消息文本错模板");
    }
}
