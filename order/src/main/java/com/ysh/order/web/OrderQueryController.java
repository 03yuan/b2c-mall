package com.ysh.order.web;

import com.ysh.b2cmall.common.response.BaseResponseVO;
import com.ysh.order.component.RequestHolderComponent;
import com.ysh.order.dao.po.OrderPageResult;
import com.ysh.order.dao.po.OrderQueryPO;
import com.ysh.order.service.OrderService;
import com.ysh.order.web.vo.OrderListVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

/**
 * 商家端订单查询工作台（shop-order-list.md 功能上下文 5.2）：
 * 分页 + 多维筛选，shopId 恒取登录上下文。
 */
@RestController
@RequestMapping("/order")
public class OrderQueryController {

    private final OrderService orderService;

    private final RequestHolderComponent requestHolder;

    public OrderQueryController() {
        this(null, null);
    }

    @Autowired
    public OrderQueryController(OrderService orderService, RequestHolderComponent requestHolder) {
        this.orderService = orderService;
        this.requestHolder = requestHolder;
    }

    /**
     * 订单列表：分页 + 多维筛选，shopId 恒取登录上下文。
     */
    @GetMapping("/page")
    public BaseResponseVO<OrderListVO> page(@RequestParam(required = false) String orderNo,
                                            @RequestParam(required = false) String orderType,
                                            @RequestParam(required = false) String orderCategory,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String receiverName,
                                            @RequestParam(required = false) String receiverPhone,
                                            @RequestParam(required = false) String payType,
                                            @RequestParam(required = false) String refundStatus,
                                            @RequestParam(required = false) String logisticsType,
                                            @RequestParam(required = false) String storeName,
                                            @RequestParam(required = false) String startDate,
                                            @RequestParam(required = false) String endDate,
                                            @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        OrderQueryPO filter = new OrderQueryPO();
        // shopId 恒取登录上下文，请求参数中的 shopId 一律忽略
        filter.setShopId(requestHolder.get().getShopId());
        filter.setOrderNo(orderNo);
        filter.setOrderType(orderType);
        filter.setOrderCategory(orderCategory);
        filter.setStatus(status);
        filter.setReceiverName(receiverName);
        filter.setReceiverPhone(receiverPhone);
        filter.setPayType(payType);
        filter.setRefundStatus(refundStatus);
        filter.setLogisticsType(logisticsType);
        filter.setStoreName(storeName);
        filter.setStartDate(startDate);
        filter.setEndDate(endDate);
        if (page != null) {
            filter.setPage(page);
        }
        if (size != null) {
            filter.setSize(size);
        }
        // 分页参数非法直接 400，不落进 SQL
        filter.validatePaging();
        // start > end 静默查空会让商家误判无单，直接拒绝
        if (isNotBlank(startDate) && isNotBlank(endDate) && startDate.compareTo(endDate) > 0) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }

        OrderPageResult pageResult = orderService.pageOrders(filter);
        OrderListVO vo = new OrderListVO();
        // 空结果为空列表 + total=0，不伪造任何数值
        vo.setList(pageResult.getRows() == null ? Collections.emptyList() : pageResult.getRows());
        vo.setTotal(pageResult.getTotal());
        return new BaseResponseVO<>(vo);
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
