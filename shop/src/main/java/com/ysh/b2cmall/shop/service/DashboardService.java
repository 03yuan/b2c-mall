package com.ysh.b2cmall.shop.service;

import com.ysh.b2cmall.shop.web.vo.DashboardSummaryVO;

import java.math.BigDecimal;

/**
 * 店铺 Dashboard 聚合服务。
 * 约定：只按登录店铺 shopId 聚合数据，不接受外部指定 shopId。
 */
public interface DashboardService {

    DashboardSummaryVO getSummary(Integer shopId);

    /**
     * 同比变化率（如 +100.00% / -50.00%）。
     * 昨日无数据（null 或 0）显示 100%；今日无数据（null 或 0）整项 null（前端「暂无数据」）。
     */
    String ratio(BigDecimal today, BigDecimal yesterday);
}
