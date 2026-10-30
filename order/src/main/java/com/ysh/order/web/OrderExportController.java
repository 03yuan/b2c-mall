package com.ysh.order.web;

import com.alibaba.excel.EasyExcel;
import com.ysh.order.component.RequestHolderComponent;
import com.ysh.order.dao.po.OrderPO;
import com.ysh.order.dao.po.OrderQueryPO;
import com.ysh.order.service.OrderService;
import com.ysh.order.web.excel.OrderExcelColumns;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 订单导出 Excel 入口（shop-order-list.md 功能上下文 8.2）：
 * GET /order/export，复用列表筛选取全量，手机号明文。
 * shopId 恒取登录上下文。
 */
@RestController
@RequestMapping("/order")
public class OrderExportController {

    private final OrderService orderService;

    private final RequestHolderComponent requestHolder;

    public OrderExportController() {
        this(null, null);
    }

    @Autowired
    public OrderExportController(OrderService orderService, RequestHolderComponent requestHolder) {
        this.orderService = orderService;
        this.requestHolder = requestHolder;
    }

    /**
     * 导出订单 Excel（8.2）：响应头 xlsx 下载流 + 时间戳文件名；表头单一列定义；手机号明文。
     * 复用列表筛选取全量（不分页），shopId 恒取登录上下文，请求参数中的 shopId 一律忽略。
     */
    @GetMapping("/export")
    public void export(@RequestParam(required = false) String orderNo,
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
                       HttpServletResponse response) throws IOException {
        // shopId 恒取登录上下文，请求参数中的 shopId 一律忽略；筛选与列表同源（复用列表筛选构建器）
        OrderQueryPO filter = new OrderQueryPO();
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
        List<OrderPO> rows = orderService.exportList(filter);

        String fileName = "订单列表_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8.name()));

        List<List<String>> head = OrderExcelColumns.HEADERS.stream()
                .map(Collections::singletonList)
                .collect(Collectors.toList());
        List<List<String>> data = rows.stream()
                .map(OrderExcelColumns::toRow)
                .collect(Collectors.toList());
        EasyExcel.write(response.getOutputStream()).head(head).sheet("订单列表").doWrite(data);
    }
}
