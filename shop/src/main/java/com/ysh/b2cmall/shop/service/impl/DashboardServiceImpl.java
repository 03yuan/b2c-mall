package com.ysh.b2cmall.shop.service.impl;

import com.ysh.b2cmall.shop.feign.OrderStatsClient;
import com.ysh.b2cmall.shop.feign.ProductStatsClient;
import com.ysh.b2cmall.shop.service.DashboardService;
import com.ysh.b2cmall.shop.web.vo.CardsVO;
import com.ysh.b2cmall.shop.web.vo.DashboardSummaryVO;
import com.ysh.b2cmall.shop.web.vo.MetricsVO;
import com.ysh.b2cmall.shop.web.vo.TrendVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 店铺 Dashboard 聚合服务：按登录 shopId 查询下游统计并组装统一 VO。
 */
@Service
public class DashboardServiceImpl implements DashboardService {

    private final OrderStatsClient orderStatsClient;
    private final ProductStatsClient productStatsClient;

    public DashboardServiceImpl(OrderStatsClient orderStatsClient, ProductStatsClient productStatsClient) {
        this.orderStatsClient = orderStatsClient;
        this.productStatsClient = productStatsClient;
    }

    @Override
    public DashboardSummaryVO getSummary(Integer shopId) {
        // 数据隔离：下游统计接口只按登录 shopId 查询
        Map<String, Object> orderStats = orderStatsClient.getStats(shopId);
        Map<String, Object> productStats = productStatsClient.getStats(shopId);

        CardsVO cards = new CardsVO();
        cards.setPendingPayment(toCardValue(orderStats, "pendingPayment"));
        cards.setPendingShipment(toCardValue(orderStats, "pendingShipment"));
        cards.setPendingReceipt(toCardValue(orderStats, "pendingReceipt"));
        cards.setCompleted(toCardValue(orderStats, "completed"));
        cards.setRefundAfterSale(toCardValue(orderStats, "refundAfterSale"));
        cards.setStockAlert(toCardValue(productStats, "stockAlert"));

        DashboardSummaryVO vo = new DashboardSummaryVO();
        vo.setShopId(shopId);
        vo.setCards(cards);
        vo.setTrend(buildTrend(orderStats));
        vo.setMetrics(new MetricsVO());
        return vo;
    }

    /** 无数据（缺 key 或 0）返回 null（前端「暂无数据」），有数据返回真实数字 */
    private Integer toCardValue(Map<String, Object> stats, String key) {
        if (stats == null) {
            return null;
        }
        Object value = stats.get(key);
        if (value == null) {
            return null;
        }
        int count = ((Number) value).intValue();
        return count == 0 ? null : count;
    }

    /** 趋势区块：固定 24 点标签（0:00~23:00）+ 今日/昨日序列 */
    private TrendVO buildTrend(Map<String, Object> orderStats) {
        TrendVO trend = new TrendVO();
        List<String> labels = new ArrayList<>(24);
        for (int h = 0; h < 24; h++) {
            labels.add(h + ":00");
        }
        trend.setLabels(labels);
        trend.setToday(toSeries(orderStats, "todayHourly"));
        trend.setYesterday(toSeries(orderStats, "yesterdayHourly"));
        return trend;
    }

    @Override
    public String ratio(BigDecimal today, BigDecimal yesterday) {
        // 今日无数据（null 或 0）整项 null（前端「暂无数据」）
        if (today == null || today.signum() == 0) {
            return null;
        }
        // 昨日无数据（null 或 0）显示 100%（需求硬规定），不抛除零异常
        if (yesterday == null || yesterday.signum() == 0) {
            return "100%";
        }
        // 同比 =（今-昨)/昨 × 100%，正数带 + 号、两位小数（如 +100.00% / -50.00%）
        BigDecimal rate = today.subtract(yesterday)
                .multiply(BigDecimal.valueOf(100))
                .divide(yesterday, 2, RoundingMode.HALF_UP);
        return String.format("%+.2f%%", rate);
    }

    /** 无数据（缺 key / null / 空数组）返回 null（前端「暂无数据」），有数据原样映射 */
    @SuppressWarnings("unchecked")
    private List<BigDecimal> toSeries(Map<String, Object> stats, String key) {
        if (stats == null) {
            return null;
        }
        Object value = stats.get(key);
        if (value == null) {
            return null;
        }
        List<BigDecimal> series = (List<BigDecimal>) value;
        return series.isEmpty() ? null : series;
    }
}
