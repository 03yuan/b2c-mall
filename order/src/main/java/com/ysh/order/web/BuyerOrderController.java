package com.ysh.order.web;

import com.ysh.b2cmall.common.response.BaseResponseVO;
import com.ysh.order.bean.CurrentEmployeeBean;
import com.ysh.order.component.RequestHolderComponent;
import com.ysh.order.dao.po.BuyerOrderQueryPO;
import com.ysh.order.service.OrderService;
import com.ysh.order.web.vo.BuyerOrderPageVO;
import com.ysh.order.web.vo.BuyerStatusCountsVO;
import com.ysh.order.web.vo.OrderDetailVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 买家端订单列表（shop-order-list.md 功能上下文 10.2）：
 * GET /order/buyer/page（状态 Tab + 订单编号精确查询）、
 * GET /order/buyer/status-counts（状态计数）。
 * 买家身份（username）强制取自登录上下文，请求参数中的 buyerUsername 一律忽略。
 */
@RestController
@RequestMapping("/order/buyer")
public class BuyerOrderController {

    @Autowired
    private OrderService orderService;

    @Autowired
    private RequestHolderComponent requestHolder;

    public BuyerOrderController() {
    }

    @Autowired
    public BuyerOrderController(OrderService orderService, RequestHolderComponent requestHolder) {
        this.orderService = orderService;
        this.requestHolder = requestHolder;
    }

    /**
     * 买家订单列表（10.2）：状态 Tab + 订单编号精确查询。
     */
    @GetMapping("/page")
    public BaseResponseVO<BuyerOrderPageVO> page(@RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String orderNo,
                                                 @RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size,
                                                 javax.servlet.http.HttpServletResponse httpResponse) {
        CurrentEmployeeBean current = requestHolder.get();
        // 无买家身份（无登录态或商家身份）-> 401，不调用 Service
        if (current == null || current.getUsername() == null || current.getUsername().isEmpty()
                || current.getShopId() != null) {
            httpResponse.setStatus(javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }
        BuyerOrderQueryPO filter = new BuyerOrderQueryPO();
        filter.setBuyerUsername(current.getUsername());
        filter.setStatus(status);
        filter.setOrderNo(orderNo);
        filter.setPage(page == null ? 1 : page);
        filter.setSize(size == null ? 10 : size);
        return new BaseResponseVO<>(orderService.pageBuyerOrders(filter));
    }

    /**
     * 买家订单状态计数（10.2）：固定五 key，无单状态为 0。
     */
    @GetMapping("/status-counts")
    public BaseResponseVO<BuyerStatusCountsVO> statusCounts(javax.servlet.http.HttpServletResponse httpResponse) {
        CurrentEmployeeBean current = requestHolder.get();
        // 无买家身份（无登录态或商家身份）-> 401，不调用 Service
        if (current == null || current.getUsername() == null || current.getUsername().isEmpty()
                || current.getShopId() != null) {
            httpResponse.setStatus(javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }
        return new BaseResponseVO<>(orderService.buyerStatusCounts(current.getUsername()));
    }

    /**
     * 买家订单详情（11.1.1）：复用 DetailVO 组装，buyer_username 隔离，他人/不存在 -> 404。
     */
    @GetMapping("/{orderId}")
    public BaseResponseVO<OrderDetailVO> detail(@PathVariable Integer orderId,
                                                javax.servlet.http.HttpServletResponse httpResponse) {
        CurrentEmployeeBean current = requestHolder.get();
        if (current == null || current.getUsername() == null || current.getUsername().isEmpty()
                || current.getShopId() != null) {
            httpResponse.setStatus(javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }
        return new BaseResponseVO<>(orderService.getBuyerOrderDetail(orderId, current.getUsername()));
    }

    /**
     * 买家逻辑删除订单（11.1.2）：幂等，buyer_username 隔离。
     */
    @PostMapping("/delete/{orderId}")
    public BaseResponseVO<?> delete(@PathVariable Integer orderId,
                                    javax.servlet.http.HttpServletResponse httpResponse) {
        CurrentEmployeeBean current = requestHolder.get();
        if (current == null || current.getUsername() == null || current.getUsername().isEmpty()
                || current.getShopId() != null) {
            httpResponse.setStatus(javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }
        orderService.deleteByBuyer(orderId, current.getUsername());
        return new BaseResponseVO<>();
    }

    /**
     * 买家取消订单（11.1.3）：仅 PENDING -> FAILED，buyer_username 隔离。
     */
    @PostMapping("/cancel/{orderId}")
    public BaseResponseVO<?> cancel(@PathVariable Integer orderId,
                                    javax.servlet.http.HttpServletResponse httpResponse) {
        CurrentEmployeeBean current = requestHolder.get();
        if (current == null || current.getUsername() == null || current.getUsername().isEmpty()
                || current.getShopId() != null) {
            httpResponse.setStatus(javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }
        orderService.cancelByBuyer(orderId, current.getUsername());
        return new BaseResponseVO<>();
    }

    /**
     * 买家收藏订单（mall-order-manage.md 功能上下文 6.2.1）：本人未删 -> 200 + fav=1，他人/不存在/已删 -> 404。
     */
    @PostMapping("/fav/{orderId}")
    public BaseResponseVO<?> fav(@PathVariable Integer orderId,
                                 javax.servlet.http.HttpServletResponse httpResponse) {
        CurrentEmployeeBean current = requestHolder.get();
        if (current == null || current.getUsername() == null || current.getUsername().isEmpty()
                || current.getShopId() != null) {
            httpResponse.setStatus(javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }
        orderService.favByBuyer(orderId, current.getUsername());
        return new BaseResponseVO<>();
    }

    /**
     * 买家取消收藏订单（mall-order-manage.md 功能上下文 6.2.2）：本人未删 -> 200 + fav=0，幂等，他人/不存在/已删 -> 404。
     */
    @PostMapping("/unfav/{orderId}")
    public BaseResponseVO<?> unfav(@PathVariable Integer orderId,
                                   javax.servlet.http.HttpServletResponse httpResponse) {
        CurrentEmployeeBean current = requestHolder.get();
        if (current == null || current.getUsername() == null || current.getUsername().isEmpty()
                || current.getShopId() != null) {
            httpResponse.setStatus(javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }
        orderService.unfavByBuyer(orderId, current.getUsername());
        return new BaseResponseVO<>();
    }

    /**
     * 买家去支付订单（mall-order-manage.md 功能上下文 7.2.1）：本人未删 PENDING -> 200，他人/不存在 -> 404，payType 必填。
     * TDD 红阶段：401 判断已落地，Service 透传待绿阶段实现。
     */
    @PostMapping("/pay/{orderId}")
    public BaseResponseVO<?> pay(@PathVariable Integer orderId,
                                 @RequestParam(required = true) String payType,
                                 javax.servlet.http.HttpServletResponse httpResponse) {
        CurrentEmployeeBean current = requestHolder.get();
        if (current == null || current.getUsername() == null || current.getUsername().isEmpty()
                || current.getShopId() != null) {
            httpResponse.setStatus(javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }
        orderService.payByBuyer(orderId, payType, current.getUsername());
        return new BaseResponseVO<>();
    }

    /**
     * 买家确认收货订单（mall-order-manage.md 功能上下文 8.2.1）：本人未删 SHIPPED -> 200，他人/不存在 -> 404。
     * TDD 红阶段：401 判断已落地，Service 透传待绿阶段实现。
     */
    @PostMapping("/confirm/{orderId}")
    public BaseResponseVO<?> confirm(@PathVariable Integer orderId,
                                     javax.servlet.http.HttpServletResponse httpResponse) {
        CurrentEmployeeBean current = requestHolder.get();
        if (current == null || current.getUsername() == null || current.getUsername().isEmpty()
                || current.getShopId() != null) {
            httpResponse.setStatus(javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }
        orderService.confirmByBuyer(orderId, current.getUsername());
        return new BaseResponseVO<>();
    }
}
