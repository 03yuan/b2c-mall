package com.ysh.b2cmall.product.client;

import com.ysh.b2cmall.common.response.BaseResponseVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * order-service 查询客户端（shop-product-list.md 功能上下文 7.3.2）。
 * 调用 order-service GET /order/sku/{skuId}/count，响应 data.count 为订单条数（shopId 由服务端隔离）。
 */
@FeignClient(name = "order-service")
public interface OrderQueryClient {

    /** 指定商品的订单条数（任意状态都算「已产生订单」） */
    @GetMapping("/order/sku/{skuId}/count")
    BaseResponseVO<Map<String, Object>> countBySku(@PathVariable("skuId") Integer skuId);
}
