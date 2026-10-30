package com.ysh.b2cmall.product.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ysh.b2cmall.product.dao.mapper.SkuMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 真实销量回写消费者（shop-product-list.md 功能上下文 8.1）。
 * 订阅 RocketMQ topic b2c-order-events、tag PAY_SUCCESS：
 * 按 skuId 累加 tb_sku.sales（累加非覆盖）；畸形/缺字段/未知 tag 忽略不抛异常；
 * 同一 orderId 幂等只累加一次。
 *
 * TDD 红阶段占位（功能上下文 8.1）：方法体留白，消费逻辑待绿阶段补全。
 */
@Slf4j
@Component
public class SalesEventConsumer {

    private final SkuMapper skuMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public SalesEventConsumer(SkuMapper skuMapper) {
        this.skuMapper = skuMapper;
    }

    /**
     * 消费一条订单事件
     * @param tag  事件标签（仅 PAY_SUCCESS 参与销量回写）
     * @param body 事件载荷 JSON（shopId、orderId、skuId、quantity）
     */
    public void onMessage(String tag, String body) {
        // 1. 未知 tag 忽略（仅 PAY_SUCCESS 回写销量，防 MQ 重试风暴）
        if (!"PAY_SUCCESS".equals(tag)) {
            log.warn("非销量回写事件，忽略：tag={}", tag);
            return;
        }

        // 2. 解析载荷（畸形 JSON 忽略）
        JsonNode payload;
        try {
            payload = objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("销量回写载荷非法，忽略：body={}", body);
            return;
        }

        // 3. 必填字段校验（缺 orderId 无法幂等去重，缺 skuId/quantity 无法累加）
        if (payload == null || !payload.hasNonNull("orderId")
                || !payload.hasNonNull("skuId") || !payload.hasNonNull("quantity")) {
            log.warn("销量回写载荷缺少必填字段，忽略：body={}", body);
            return;
        }

        // 4. 幂等：同一 orderId 只累加一次（tb_processed_event 主键去重）
        int orderId = payload.get("orderId").asInt();
        if (skuMapper.insertProcessedEvent(orderId) == 0) {
            log.info("订单事件已处理过，跳过：orderId={}", orderId);
            return;
        }

        // 5. 累加销量（累加非覆盖；未知 skuId 影响 0 行不抛异常）
        skuMapper.incrementSales(payload.get("skuId").asInt(), payload.get("quantity").asInt());
    }
}
