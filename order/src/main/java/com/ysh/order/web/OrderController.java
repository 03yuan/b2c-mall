package com.ysh.order.web;

import com.ysh.b2cmall.common.response.BaseResponseVO;
import com.ysh.order.dao.po.OrderPO;
import com.ysh.order.service.OrderService;
import com.ysh.order.web.request.CreateOrderRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/order")
public class OrderController {

    @Autowired
    private OrderService orderService;

    // 暂时硬编码，后续接回token后从 RequestHolderComponent 获取
    private static final Integer DEFAULT_SHOP_ID = 1;
    private static final Integer DEFAULT_OPERATOR_ID = 1;

    /**
     * 下单
     */
    @PostMapping("/create")
    public BaseResponseVO<Map<String, Object>> create(@RequestBody @Validated CreateOrderRequest req) {
        Map<String, Object> data = orderService.createOrder(
                DEFAULT_SHOP_ID,
                DEFAULT_OPERATOR_ID,
                req.getSkuId(),
                req.getSkuName(),
                req.getQuantity(),
                req.getPrice(),
                req.getReceiverName(),
                req.getReceiverPhone(),
                req.getReceiverAddress()
        );
        return new BaseResponseVO<>(data);
    }

    /**
     * 支付（策略模式选支付方式 + 状态模式流转）
     */
    @PostMapping("/pay/{orderId}")
    public BaseResponseVO<?> pay(@PathVariable Integer orderId,
                                 @RequestParam String payType) {
        orderService.pay(orderId, payType, DEFAULT_OPERATOR_ID);
        return new BaseResponseVO<>();
    }

    /**
     * 发货（状态模式流转：PAID -> SHIPPED）
     */
    @PostMapping("/ship/{orderId}")
    public BaseResponseVO<?> ship(@PathVariable Integer orderId) {
        orderService.ship(orderId, DEFAULT_OPERATOR_ID);
        return new BaseResponseVO<>();
    }

    /**
     * 确认收货（状态模式流转：SHIPPED -> COMPLETED）
     */
    @PostMapping("/confirm/{orderId}")
    public BaseResponseVO<?> confirm(@PathVariable Integer orderId) {
        orderService.confirm(orderId, DEFAULT_OPERATOR_ID);
        return new BaseResponseVO<>();
    }

    /**
     * 查询订单
     */
    @GetMapping("/{orderId}")
    public BaseResponseVO<OrderPO> getOrder(@PathVariable Integer orderId) {
        OrderPO order = orderService.getOrder(orderId);
        return new BaseResponseVO<>(order);
    }
}
