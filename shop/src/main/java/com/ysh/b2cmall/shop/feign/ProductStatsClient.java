package com.ysh.b2cmall.shop.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 商品统计 Feign 客户端：按 shopId 拉取商品侧统计（库存预警计数等）。
 */
@FeignClient(name = "product-service", path = "/product")
public interface ProductStatsClient {

    @GetMapping("/stats/summary")
    Map<String, Object> getStats(@RequestParam("shopId") Integer shopId);
}
