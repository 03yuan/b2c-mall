package com.ysh.order.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ysh.order.dao.po.OrderPO;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 订单领域事件发布（RocketMQ，topic：b2c-order-events）。
 * 三种事件 tag：PAY_SUCCESS / REFUND_APPLY / CONFIRM_RECEIPT，不得混用。
 */
@Component
public class OrderEventPublisher {

    /** 订单事件统一 topic */
    static final String TOPIC = "b2c-order-events";

    private final MqProducer mqProducer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OrderEventPublisher(MqProducer mqProducer) {
        this.mqProducer = mqProducer;
    }

    /** 支付成功事件（tag：PAY_SUCCESS），载荷含 shopId、orderId、buyerUsername、时间戳 */
    public void publishPaySuccess(OrderPO order) {
        publish("PAY_SUCCESS", order);
    }

    /** 退款申请事件（tag：REFUND_APPLY），载荷含 shopId、orderId、buyerUsername、时间戳 */
    public void publishRefundApply(OrderPO order) {
        publish("REFUND_APPLY", order);
    }

    /** 确认收货事件（tag：CONFIRM_RECEIPT），载荷含 shopId、orderId、buyerUsername、时间戳 */
    public void publishConfirmReceipt(OrderPO order) {
        publish("CONFIRM_RECEIPT", order);
    }

    /** 统一组装载荷并发送：shopId/orderId/buyerUsername/时间戳；PAY_SUCCESS 附带 skuId/quantity 供销量回写（功能上下文 8.1） */
    private void publish(String tag, OrderPO order) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("shopId", order.getShopId());
        payload.put("orderId", order.getId());
        payload.put("buyerUsername", order.getBuyerUsername());
        payload.put("timestamp", System.currentTimeMillis());
        if ("PAY_SUCCESS".equals(tag)) {
            payload.put("skuId", order.getSkuId());
            payload.put("quantity", order.getQuantity());
        }
        try {
            mqProducer.send(TOPIC, tag, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("事件载荷序列化失败", e);
        }
    }
}
