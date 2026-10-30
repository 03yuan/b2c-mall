package com.ysh.b2cmall.shop.web.vo;

import lombok.Data;

/**
 * Dashboard 统一入口响应 VO：一次返回卡片 + 趋势 + 指标三个区块。
 */
@Data
public class DashboardSummaryVO {

    /** 登录店铺 id（由登录上下文注入，不接受请求参数指定） */
    private Integer shopId;

    /** 顶部 6 个订单状态统计卡片 */
    private CardsVO cards;

    /** 今日 vs 昨日 24 小时交易金额趋势 */
    private TrendVO trend;

    /** 4 项核心经营指标（含同比昨日变化率） */
    private MetricsVO metrics;
}
