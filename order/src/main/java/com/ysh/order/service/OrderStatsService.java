package com.ysh.order.service;

import com.ysh.order.dao.po.MetricsPO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 订单统计服务：Dashboard 趋势/指标的统计口径。
 */
public interface OrderStatsService {

    /** 指定自然日 24 个整点的已支付金额序列（缺失小时补 0）；全天无支付返回 null（前端「暂无数据」） */
    List<BigDecimal> hourlyPaidAmounts(Integer shopId, LocalDate day);

    /** 指定自然日的经营指标（支付客户数/支付订单数/退款金额/客单价） */
    MetricsPO dailyMetrics(Integer shopId, LocalDate day);
}
