package com.ysh.b2cmall.shop.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ysh.b2cmall.shop.dao.mapper.ShopMessageMapper;
import com.ysh.b2cmall.shop.dao.po.ShopMessagePO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 订单事件消费者（RocketMQ，topic：b2c-order-events）。
 * 收到事件后按固定模板拼出消息文本并落库 tb_shop_message。
 * 三种事件 tag：PAY_SUCCESS / REFUND_APPLY / CONFIRM_RECEIPT。
 */
@Slf4j
@Component
public class MessageConsumer {

    private final ShopMessageMapper shopMessageMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MessageConsumer(ShopMessageMapper shopMessageMapper) {
        this.shopMessageMapper = shopMessageMapper;
    }

    /**
     * 消费一条订单事件
     * @param tag  事件标签（PAY_SUCCESS / REFUND_APPLY / CONFIRM_RECEIPT）
     * @param body 事件载荷 JSON（shopId、orderId、buyerUsername、时间戳）
     */
    public void onMessage(String tag, String body) {
        // 1. 解析载荷（非法 JSON 不落库）
        JsonNode payload;
        try {
            payload = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            log.warn("消息载荷非法，忽略：tag={}, body={}", tag, body);
            return;
        }

        // 2. 必填字段校验（缺 shopId/orderId/buyerUsername 无法归属店铺/拼模板，不落库）
        if (payload == null || !payload.hasNonNull("shopId") || !payload.hasNonNull("orderId")
                || !payload.hasNonNull("buyerUsername")) {
            log.warn("消息载荷缺少必填字段，忽略：tag={}, body={}", tag, body);
            return;
        }

        // 3. 未知事件类型不落库（防止产生错模板的脏消息）
        MessageTypeEnum type;
        try {
            type = MessageTypeEnum.valueOf(tag);
        } catch (IllegalArgumentException e) {
            log.warn("未知事件类型，忽略：tag={}", tag);
            return;
        }

        // 4. 按固定模板拼文本并落库
        Integer orderId = payload.get("orderId").asInt();
        ShopMessagePO message = new ShopMessagePO();
        message.setShopId(payload.get("shopId").asInt());
        message.setOrderId(orderId);
        message.setType(type.name());
        message.setContent(type.buildContent(orderId, payload.get("buyerUsername").asText()));
        shopMessageMapper.insert(message);
    }
}
