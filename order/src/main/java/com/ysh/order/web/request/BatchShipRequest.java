package com.ysh.order.web.request;

import lombok.Data;

import java.util.List;

/**
 * 批量发货请求体（.tdd/task/shop-order-list.md 功能上下文 7.2）：
 * items 逐条独立处理，单条 {orderId, logisticsNo, logisticsType}。
 * （TDD 红阶段：仅数据结构，供测试编译通过。）
 */
@Data
public class BatchShipRequest {

    /** 待发货订单列表（空列表 / 缺失视为非法输入 -> 400） */
    private List<Item> items;

    @Data
    public static class Item {
        private Integer orderId;
        private String logisticsNo;
        private String logisticsType;
    }
}
