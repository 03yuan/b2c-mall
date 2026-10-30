package com.ysh.order.web.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量发货结果（.tdd/task/shop-order-list.md 功能上下文 7.2）：
 * 逐条独立处理——成功条数 + 失败列表（orderId + 中文原因），好单照常发货不连坐。
 * （TDD 红阶段：仅数据结构，供测试编译通过。）
 */
@Data
public class BatchShipResult {

    /** 成功发货条数 */
    private int success;

    /** 失败列表（每条含 orderId 与中文原因） */
    private List<FailedItem> failed = new ArrayList<>();

    @Data
    public static class FailedItem {
        private Integer orderId;
        private String reason;
    }
}
