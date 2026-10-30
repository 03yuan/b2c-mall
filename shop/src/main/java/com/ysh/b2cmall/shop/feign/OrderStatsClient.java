package com.ysh.b2cmall.shop.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 订单统计 Feign 客户端：按 shopId 拉取订单侧统计（状态计数/小时趋势/指标）。
 */
@FeignClient(name = "order-service", path = "/order")
public interface OrderStatsClient {

    @GetMapping("/stats/summary")
    Map<String, Object> getStats(@RequestParam("shopId") Integer shopId);
}
