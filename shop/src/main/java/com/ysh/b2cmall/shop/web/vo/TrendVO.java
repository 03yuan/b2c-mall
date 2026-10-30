package com.ysh.b2cmall.shop.web.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 今日 vs 昨日 24 小时交易金额趋势（X 轴 0:00~23:00 共 24 个点）。
 */
@Data
public class TrendVO {

    /** 24 小时标签（0:00 ~ 23:00） */
    private List<String> labels;

    /** 今日各小时支付金额（蓝线），全天无支付时为 null */
    private List<BigDecimal> today;

    /** 昨日各小时支付金额（绿线），全天无支付时为 null */
    private List<BigDecimal> yesterday;
}
