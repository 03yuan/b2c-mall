package com.ysh.order.service.impl;

import com.ysh.order.dao.OrderMapper;
import com.ysh.order.service.OrderStatsService;
import org.springframework.stereotype.Service;

import com.ysh.order.dao.po.HourAmountPO;
import com.ysh.order.dao.po.MetricsPO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 订单统计服务实现。
 */
@Service
public class OrderStatsServiceImpl implements OrderStatsService {

    private final OrderMapper orderMapper;

    public OrderStatsServiceImpl(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Override
    public List<BigDecimal> hourlyPaidAmounts(Integer shopId, LocalDate day) {
        String dayStart = day + " 00:00:00";
        String dayEnd = day.plusDays(1) + " 00:00:00";
        List<HourAmountPO> rows = orderMapper.sumPaidAmountByHour(shopId, dayStart, dayEnd);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        List<BigDecimal> amounts = new ArrayList<>(Collections.nCopies(24, BigDecimal.ZERO));
        for (HourAmountPO row : rows) {
            amounts.set(row.getHour(), row.getAmount());
        }
        return amounts;
    }

    @Override
    public MetricsPO dailyMetrics(Integer shopId, LocalDate day) {
        String dayStart = day + " 00:00:00";
        String dayEnd = day.plusDays(1) + " 00:00:00";

        MetricsPO metrics = new MetricsPO();
        metrics.setPayCustomers(orderMapper.countDistinctPaidBuyers(shopId, dayStart, dayEnd));
        int payOrders = orderMapper.countPaidOrders(shopId, dayStart, dayEnd);
        metrics.setPayOrders(payOrders);
        BigDecimal refundAmount = orderMapper.sumRefundAmount(shopId, dayStart, dayEnd);
        metrics.setRefundAmount(refundAmount == null ? BigDecimal.ZERO : refundAmount);
        BigDecimal payAmount = orderMapper.sumPaidAmount(shopId, dayStart, dayEnd);
        // 客单价 = 今日总支付金额 ÷ 支付订单数；0 单时为 null（暂无数据），不抛除零
        metrics.setAvgOrderValue(payOrders == 0 ? null
                : payAmount.divide(BigDecimal.valueOf(payOrders), 2, RoundingMode.HALF_UP));
        return metrics;
    }
}
