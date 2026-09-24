package com.ysh.order.service.impl;

import com.ysh.order.bean.CurrentEmployeeBean;
import com.ysh.order.component.RequestHolderComponent;
import com.ysh.order.dao.OrderLogMapper;
import com.ysh.order.dao.OrderMapper;
import com.ysh.order.dao.po.OrderLogPO;
import com.ysh.order.dao.po.OrderPO;
import com.ysh.order.enums.OrderStatusEnum;
import com.ysh.order.enums.PayTypeEnum;
import com.ysh.order.service.OrderService;
import com.ysh.order.service.PayStrategyService;
import com.ysh.order.statemachine.OrderState;
import com.ysh.order.statemachine.OrderStateFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderLogMapper orderLogMapper;
    @Autowired
    private OrderStateFactory stateFactory;
    @Autowired
    private RequestHolderComponent requestHolder;
    @Autowired
    private List<PayStrategyService> payStrategies;

    @Override
    public Map<String, Object> createOrder(Integer shopId, Integer employeeId,
                                           Integer skuId, String skuName,
                                           Integer quantity, BigDecimal price,
                                           String receiverName, String receiverPhone, String receiverAddress) {
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("购买数量必须大于0");
        }
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("单价必须大于0");
        }

        BigDecimal totalPrice = price.multiply(BigDecimal.valueOf(quantity));

        OrderPO order = new OrderPO();
        order.setOrderNo(generateOrderNo());
        order.setShopId(shopId);
        order.setSkuId(skuId);
        order.setSkuName(skuName);
        order.setQuantity(quantity);
        order.setTotalPrice(totalPrice);
        order.setStatus(OrderStatusEnum.PENDING.name());
        order.setReceiverName(receiverName);
        order.setReceiverPhone(receiverPhone);
        order.setReceiverAddress(receiverAddress);

        orderMapper.insert(order);

        Map<String, Object> data = new HashMap<>();
        data.put("orderId", order.getId());
        data.put("orderNo", order.getOrderNo());
        data.put("totalPrice", order.getTotalPrice());
        data.put("status", order.getStatus());
        return data;
    }

    @Override
    public void pay(Integer orderId, String payType, Integer operatorId) {
        OrderPO order = getOrder(orderId);

        // 1. 策略模式：选择支付方式
        PayTypeEnum payTypeEnum = PayTypeEnum.fromString(payType);
        PayStrategyService strategy = payStrategies.stream()
                .filter(s -> s.getPayType().equalsIgnoreCase(payTypeEnum.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的支付方式: " + payType));

        // 2. 执行支付
        boolean success = strategy.pay(order.getOrderNo(), order.getTotalPrice());
        if (!success) {
            throw new RuntimeException("支付失败");
        }

        // 3. 状态模式：流转订单状态 PENDING -> PAID
        transitionState(order, "PAY", strategy.getPayType(), operatorId);
        log.info("订单支付成功：orderId={}, payType={}", orderId, payType);
    }

    @Override
    public void ship(Integer orderId, Integer operatorId) {
        OrderPO order = getOrder(orderId);

        // 状态模式：PAID -> SHIPPED
        transitionState(order, "SHIP", null, operatorId);
        log.info("订单发货成功：orderId={}", orderId);
    }

    @Override
    public void confirm(Integer orderId, Integer operatorId) {
        OrderPO order = getOrder(orderId);

        // 状态模式：SHIPPED -> COMPLETED
        transitionState(order, "CONFIRM", null, operatorId);
        log.info("订单签收成功：orderId={}", orderId);
    }

    @Override
    public OrderPO getOrder(Integer orderId) {
        OrderPO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在: " + orderId);
        }
        return order;
    }

    /**
     * 状态流转通用方法
     * @param order 订单
     * @param event 事件名称（PAY/SHIP/CONFIRM）
     * @param payType 支付方式（仅PAY时传入，其他传null）
     * @param operatorId 操作人
     */
    private void transitionState(OrderPO order, String event, String payType, Integer operatorId) {
        // 从DB状态恢复状态对象
        OrderState currentState = stateFactory.getState(order.getStatus());

        // 根据事件调用对应方法，状态类内部校验合法性（不合法会抛IllegalStateException）
        OrderState newState;
        switch (event) {
            case "PAY":
                newState = currentState.pay();
                break;
            case "SHIP":
                newState = currentState.ship();
                break;
            case "CONFIRM":
                newState = currentState.confirm();
                break;
            default:
                throw new IllegalArgumentException("未知事件: " + event);
        }

        // 更新DB
        orderMapper.updateStatus(order.getId(), newState.getStatus().name(), payType);

        // 写日志
        OrderLogPO logEntry = new OrderLogPO();
        logEntry.setOrderId(order.getId());
        logEntry.setFromStatus(order.getStatus());
        logEntry.setToStatus(newState.getStatus().name());
        logEntry.setEvent(event);
        logEntry.setOperatorId(operatorId);
        logEntry.setRemark(event + ": " + order.getStatus() + " -> " + newState.getStatus().name());
        orderLogMapper.insert(logEntry);
    }

    /**
     * 生成订单号：年月日时分秒 + 6位随机数
     */
    private String generateOrderNo() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));
    }
}
