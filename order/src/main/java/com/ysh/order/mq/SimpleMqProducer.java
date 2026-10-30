package com.ysh.order.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * MQ 发送模拟实现（真实 RocketMQ 接入在联调阶段替换）。
 */
@Slf4j
@Service
public class SimpleMqProducer implements MqProducer {

    @Override
    public void send(String topic, String tag, String body) {
        log.info("MQ模拟发送：topic={}, tag={}, body={}", topic, tag, body);
    }
}
