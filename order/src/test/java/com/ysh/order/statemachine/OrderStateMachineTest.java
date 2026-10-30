package com.ysh.order.statemachine;

import com.ysh.order.enums.OrderStatusEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 订单状态机扩展的表格驱动测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 3.1：
 * - 3.1.1 OrderStatusEnum 新增 FAILED / GROUP_PENDING；终端状态类 pay/ship/confirm/cancel 全抛异常；
 *         OrderStateFactory 补充两个 DB 值映射
 *   （Fail 边界：工厂漏映射 -> 含新状态的旧数据恢复状态对象时空指针 500）
 * - 3.1.2 OrderState 新增 cancel()；PendingState.cancel() -> FailedState；
 *         PaidState/ShippedState/CompletedState.cancel() 抛异常（已付款需走退款而非取消）
 *   （Fail 边界：任意状态可取消 -> 已付款订单被绕过退款直接取消）
 *
 * 用例分类：
 * 1. 字段级校验：6 个 DB status 值都能映射到对应状态对象
 * 2. 类型与格式：枚举常量集合、字符串解析、非法状态字符串
 * 3. 重复创建：重复取状态行为一致；同一状态重复调用 pay() 结果幂等（纯函数）
 * 4. 非法输入：终态各操作、非待付款状态 cancel 均拒绝
 *
 * 说明：断言以 getStatus() 判定状态身份，避免依赖具体状态类名，
 * 使状态类可自由重命名而不影响契约（也便于红阶段编译）。
 */
class OrderStateMachineTest {

    private final OrderStateFactory factory = new OrderStateFactory();

    // ============ 1. 字段级校验：DB 值 -> 状态对象 ============

    /**
     * 列：用例名、DB status 值、期望状态枚举
     */
    static Stream<Arguments> dbStatusCases() {
        return Stream.of(
                arguments("PENDING 映射待付款状态", "PENDING", OrderStatusEnum.PENDING),
                arguments("PAID 映射已付款状态", "PAID", OrderStatusEnum.PAID),
                arguments("SHIPPED 映射已发货状态", "SHIPPED", OrderStatusEnum.SHIPPED),
                arguments("COMPLETED 映射已完成状态", "COMPLETED", OrderStatusEnum.COMPLETED),
                arguments("FAILED 映射交易失败状态（新增）", "FAILED", OrderStatusEnum.FAILED),
                arguments("GROUP_PENDING 映射待成团状态（新增）", "GROUP_PENDING", OrderStatusEnum.GROUP_PENDING)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dbStatusCases")
    void 工厂按DB值返回对应状态(String caseName, String dbStatus, OrderStatusEnum expected) {
        OrderState state = factory.getState(dbStatus);

        assertNotNull(state, caseName + "：不得返回 null");
        assertEquals(expected, state.getStatus(), caseName + "：状态枚举不正确");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dbStatusCases")
    void 工厂按枚举返回对应状态(String caseName, String dbStatus, OrderStatusEnum expected) {
        OrderState state = factory.getState(expected);

        assertNotNull(state, caseName + "：不得返回 null");
        assertEquals(expected, state.getStatus(), caseName + "：状态枚举不正确");
    }

    // ============ 2. 类型与格式：枚举与字符串解析边界 ============

    @Test
    void 状态枚举包含6个取值() {
        OrderStatusEnum[] values = OrderStatusEnum.values();

        assertEquals(6, values.length, "状态枚举应为 6 个取值");
        assertEquals(OrderStatusEnum.FAILED, OrderStatusEnum.valueOf("FAILED"), "FAILED 枚举名不正确");
        assertEquals(OrderStatusEnum.GROUP_PENDING, OrderStatusEnum.valueOf("GROUP_PENDING"),
                "GROUP_PENDING 枚举名不正确");
    }

    /**
     * 列：用例名、非法状态字符串
     */
    static Stream<Arguments> invalidStatusCases() {
        return Stream.of(
                arguments("未定义状态字符串", "UNKNOWN"),
                arguments("小写状态字符串不匹配", "pending"),
                arguments("空前缀状态字符串", " FAILED"),
                arguments("空串", "")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidStatusCases")
    void 非法状态字符串_抛出明确异常(String caseName, String statusStr) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.getState(statusStr), caseName + "：应抛 IllegalArgumentException");
        assertTrue(ex.getMessage() != null && !ex.getMessage().trim().isEmpty(),
                caseName + "：异常消息不得为空");
    }

    // ============ 3. 重复创建：重复取状态与纯函数流转 ============

    @Test
    void 重复获取同一状态_行为一致() {
        OrderState first = factory.getState(OrderStatusEnum.FAILED);
        OrderState second = factory.getState(OrderStatusEnum.FAILED);

        assertEquals(first.getStatus(), second.getStatus(), "重复获取的状态应一致");
        IllegalStateException firstEx = assertThrows(IllegalStateException.class, first::pay);
        IllegalStateException secondEx = assertThrows(IllegalStateException.class, second::pay);
        assertEquals(firstEx.getClass(), secondEx.getClass(), "重复调用的异常类型应一致");
    }

    @Test
    void 待付款重复pay_每次均得到已付款状态() {
        OrderState pending = factory.getState(OrderStatusEnum.PENDING);

        OrderState first = pending.pay();
        OrderState second = pending.pay();

        assertNotSame(first, second, "每次流转应返回新的状态对象（不可共享可变实例）");
        assertEquals(OrderStatusEnum.PAID, first.getStatus(), "第 1 次 pay 应进入 PAID");
        assertEquals(OrderStatusEnum.PAID, second.getStatus(), "第 2 次 pay 应进入 PAID");
    }

    // ============ 4. 非法输入：终态与已付款状态拒绝操作 ============

    /**
     * 列：用例名、状态、操作名
     */
    static Stream<Arguments> terminalStateRejectCases() {
        return Stream.of(
                // ----FAILED 终态：全部操作拒绝----
                arguments("FAILED 不能付款", OrderStatusEnum.FAILED, "pay"),
                arguments("FAILED 不能发货", OrderStatusEnum.FAILED, "ship"),
                arguments("FAILED 不能确认收货", OrderStatusEnum.FAILED, "confirm"),
                arguments("FAILED 不能取消", OrderStatusEnum.FAILED, "cancel"),
                // ----GROUP_PENDING 暂无可达流转----
                arguments("GROUP_PENDING 不能付款", OrderStatusEnum.GROUP_PENDING, "pay"),
                arguments("GROUP_PENDING 不能发货", OrderStatusEnum.GROUP_PENDING, "ship"),
                arguments("GROUP_PENDING 不能确认收货", OrderStatusEnum.GROUP_PENDING, "confirm"),
                // ----已付款/已发货/已完成不可取消（须走退款）----
                arguments("PAID 不能取消", OrderStatusEnum.PAID, "cancel"),
                arguments("SHIPPED 不能取消", OrderStatusEnum.SHIPPED, "cancel"),
                arguments("COMPLETED 不能取消", OrderStatusEnum.COMPLETED, "cancel")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("terminalStateRejectCases")
    void 非法流转_抛出状态异常且消息明确(String caseName, OrderStatusEnum status, String action) {
        OrderState state = factory.getState(status);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> dispatch(state, action), caseName + "：应抛 IllegalStateException");
        assertTrue(ex.getMessage() != null && !ex.getMessage().trim().isEmpty(),
                caseName + "：异常消息不得为空");
    }

    private static OrderState dispatch(OrderState state, String action) {
        return switch (action) {
            case "pay" -> state.pay();
            case "ship" -> state.ship();
            case "confirm" -> state.confirm();
            case "cancel" -> state.cancel();
            default -> throw new IllegalArgumentException("未知操作: " + action);
        };
    }

    // ============ 4. 非法输入：待付款取消是唯一合法入口 ============

    @Test
    void 待付款取消_流转到交易失败() {
        OrderState pending = factory.getState(OrderStatusEnum.PENDING);

        OrderState cancelled = pending.cancel();

        assertNotNull(cancelled, "取消后不得返回 null");
        assertEquals(OrderStatusEnum.FAILED, cancelled.getStatus(), "待付款取消后应进入 FAILED");
        assertFalse(terminalAllowsAction(cancelled), "FAILED 应为终态，不允许任何流转");
    }

    private static boolean terminalAllowsAction(OrderState state) {
        return Stream.of("pay", "ship", "confirm", "cancel")
                .anyMatch(action -> {
                    try {
                        dispatch(state, action);
                        return true;
                    } catch (IllegalStateException e) {
                        return false;
                    }
                });
    }
}
