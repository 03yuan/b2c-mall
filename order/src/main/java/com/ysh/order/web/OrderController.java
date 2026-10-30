package com.ysh.order.web;

import com.ysh.b2cmall.common.response.BaseResponseVO;
import com.ysh.order.bean.CurrentEmployeeBean;
import com.ysh.order.component.RequestHolderComponent;
import com.ysh.order.service.OrderService;
import com.ysh.order.web.request.BatchShipRequest;
import com.ysh.order.web.request.CreateOrderRequest;
import com.ysh.order.web.vo.BatchShipResult;
import com.ysh.order.web.vo.BuyerContactVO;
import com.ysh.order.web.vo.OrderDetailVO;
import com.ysh.order.web.vo.RefundInfoVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/order")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @Autowired
    private RequestHolderComponent requestHolder;

    // 暂时硬编码，后续接回token后从 RequestHolderComponent 获取
    private static final Integer DEFAULT_SHOP_ID = 1;
    private static final Integer DEFAULT_OPERATOR_ID = 1;

    public OrderController() {
    }

    /**
     * TDD 红阶段占位构造：功能上下文 2.2.3（下单写入 buyer_username）尚未实现。
     */
    @Autowired
    public OrderController(OrderService orderService, RequestHolderComponent requestHolder) {
        this.orderService = orderService;
        this.requestHolder = requestHolder;
    }

    /**
     * 下单（买家接口：buyer_username 取自登录上下文）
     */
    @PostMapping("/create")
    public BaseResponseVO<Map<String, Object>> create(@RequestBody @Validated CreateOrderRequest req,
                                                      javax.servlet.http.HttpServletResponse httpResponse) {
        CurrentEmployeeBean current = requestHolder.get();
        String buyerUsername = current == null ? null : current.getUsername();
        if (buyerUsername == null || buyerUsername.trim().isEmpty()) {
            httpResponse.setStatus(javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }

        Map<String, Object> data = orderService.createOrder(
                DEFAULT_SHOP_ID,
                DEFAULT_OPERATOR_ID,
                req.getSkuId(),
                req.getSkuName(),
                req.getQuantity(),
                req.getPrice(),
                req.getReceiverName(),
                req.getReceiverPhone(),
                req.getReceiverAddress(),
                req.getShopName(),
                req.getSkuImage(),
                req.getShopPhone(),
                buyerUsername
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
     * 指定商品的订单条数（商品删除前校验，shop-product-list.md 功能上下文 7.3.1）
     * shopId 隔离，任意状态的订单都算「已产生订单」
     */
    @GetMapping("/sku/{skuId}/count")
    public BaseResponseVO<Map<String, Object>> countBySku(@PathVariable Integer skuId) {
        int count = orderService.countBySkuId(skuId, DEFAULT_SHOP_ID);
        Map<String, Object> data = new HashMap<>();
        data.put("count", count);
        return new BaseResponseVO<>(data);
    }

    /**
     * 发货（状态模式流转：PAID -> SHIPPED；携带物流单号与物流类型）
     * 参数校验（7.1.2）：物流单号非空、物流类型在枚举内；shopId 强制取自登录上下文
     */
    @PostMapping("/ship/{orderId}")
    public BaseResponseVO<?> ship(@PathVariable Integer orderId,
                                  @RequestParam String logisticsNo,
                                  @RequestParam String logisticsType,
                                  javax.servlet.http.HttpServletResponse httpResponse) {
        CurrentEmployeeBean current = requestHolder.get();
        Integer shopId = current == null ? null : current.getShopId();
        // 无商家身份（shopId 为空）-> 401，不调用 Service
        if (shopId == null) {
            httpResponse.setStatus(javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }
        // 参数校验（7.1.2）：物流单号非空 -> 400 中文原因，不调用 Service
        if (logisticsNo == null || logisticsNo.trim().isEmpty()) {
            throw new IllegalArgumentException("物流单号不能为空");
        }
        // 物流类型在枚举内（EXPRESS/CITY，大小写/空格归一化）-> 400 中文原因，不调用 Service
        String normalizedType = logisticsType == null ? null : logisticsType.trim().toUpperCase();
        if (!"EXPRESS".equals(normalizedType) && !"CITY".equals(normalizedType)) {
            throw new IllegalArgumentException("物流类型非法（仅支持 EXPRESS / CITY）");
        }

        orderService.ship(orderId, logisticsNo.trim(), normalizedType, shopId, DEFAULT_OPERATOR_ID);
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
     * 批量发货（shop-order-list.md 功能上下文 7.2）：items 逐条独立处理，失败汇总。
     * shopId 恒取登录上下文；items 为空 -> 400；无商家身份 -> 401。
     */
    @PostMapping("/batch-ship")
    public BaseResponseVO<BatchShipResult> batchShip(@RequestBody BatchShipRequest req,
                                                     javax.servlet.http.HttpServletResponse httpResponse) {
        CurrentEmployeeBean current = requestHolder.get();
        Integer shopId = current == null ? null : current.getShopId();
        // 无商家身份（shopId 为空）-> 401，不调用 Service
        if (shopId == null) {
            httpResponse.setStatus(javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }
        // 入参校验（7.2.2）：items 为空 -> 400 中文原因，不调用 Service
        if (req == null || req.getItems() == null || req.getItems().isEmpty()) {
            throw new IllegalArgumentException("批量发货列表不能为空");
        }

        BatchShipResult result = orderService.batchShip(req.getItems(), shopId, DEFAULT_OPERATOR_ID);
        return new BaseResponseVO<>(result);
    }

    /**
     * 订单详情（增强 GET /order/{orderId}，shop-order-list.md 功能上下文 9.1）：
     * 完整明细 + 状态流转日志，shopId 强制取自登录上下文，他店/不存在 -> 404。
     */
    @GetMapping("/{orderId}")
    public BaseResponseVO<OrderDetailVO> getOrder(@PathVariable Integer orderId,
                                                  javax.servlet.http.HttpServletResponse httpResponse) {
        CurrentEmployeeBean current = requestHolder.get();
        Integer shopId = current == null ? null : current.getShopId();
        // 无商家身份（shopId 为空）-> 401，不调用 Service
        if (shopId == null) {
            httpResponse.setStatus(javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }
        OrderDetailVO detail = orderService.getOrderDetail(orderId, shopId);
        return new BaseResponseVO<>(detail);
    }

    /**
     * 联系买家（shop-order-list.md 功能上下文 9.2）：买家账号 + 脱敏手机号，shopId 强制隔离。
     */
    @GetMapping("/{orderId}/buyer-contact")
    public BaseResponseVO<BuyerContactVO> buyerContact(@PathVariable Integer orderId,
                                                       javax.servlet.http.HttpServletResponse httpResponse) {
        CurrentEmployeeBean current = requestHolder.get();
        Integer shopId = current == null ? null : current.getShopId();
        // 无商家身份（shopId 为空）-> 401，不调用 Service
        if (shopId == null) {
            httpResponse.setStatus(javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }
        BuyerContactVO contact = orderService.getBuyerContact(orderId, shopId);
        return new BaseResponseVO<>(contact);
    }

    /**
     * 退款信息（shop-order-list.md 功能上下文 9.3）：有单据返回详情，无单据返回 null，shopId 强制隔离。
     */
    @GetMapping("/{orderId}/refund")
    public BaseResponseVO<RefundInfoVO> refundInfo(@PathVariable Integer orderId,
                                                   javax.servlet.http.HttpServletResponse httpResponse) {
        CurrentEmployeeBean current = requestHolder.get();
        Integer shopId = current == null ? null : current.getShopId();
        // 无商家身份（shopId 为空）-> 401，不调用 Service
        if (shopId == null) {
            httpResponse.setStatus(javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }
        RefundInfoVO info = orderService.getRefundInfo(orderId, shopId);
        return new BaseResponseVO<>(info);
    }
}
