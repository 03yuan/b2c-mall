package com.ysh.order.service.impl;

import com.ysh.order.service.PayStrategyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 微信支付策略（模拟实现）
 */
@Slf4j
@Service
public class WechatStrategyService implements PayStrategyService {

    @Override
    public boolean pay(String orderNo, BigDecimal amount) {
        log.info("微信支付模拟：orderNo={}, amount={}", orderNo, amount);
        return true;
    }

    @Override
    public String getPayType() {
        return "WECHAT";
    }
}
