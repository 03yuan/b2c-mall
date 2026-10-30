package com.ysh.order.mq;

/**
 * MQ 发送抽象（真实 RocketMQ 接入在联调阶段，测试用 mock producer）。
 */
public interface MqProducer {

    /**
     * 发送一条消息
     * @param topic 主题（订单事件固定 b2c-order-events）
     * @param tag   事件标签（PAY_SUCCESS / REFUND_APPLY / CONFIRM_RECEIPT）
     * @param body  消息体（事件载荷 JSON）
     */
    void send(String topic, String tag, String body);
}
