package com.ysh.order.web.excel;

import com.ysh.order.dao.po.OrderPO;

import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 订单 Excel 列定义（shop-order-list.md 功能上下文 8.2）：
 * 导出表头的单一列定义来源，防止表头两处硬编码导致后续改列漂移。
 * 列顺序即 Excel 列顺序。手机号导出口径为明文（商家履约需要，与列表脱敏差异显式固化于此）。
 */
public final class OrderExcelColumns {

    private OrderExcelColumns() {
    }

    /** 表头列（含明文联系电话） */
    public static final List<String> HEADERS = Collections.unmodifiableList(Arrays.asList(
            "订单编号", "下单时间", "订单类型", "订单状态", "收件人姓名", "联系电话",
            "支付方式", "物流类型", "物流单号", "门店名称", "商品名称", "成交单价",
            "数量", "实付金额"));

    /**
     * PO → Excel 数据行（列序与 HEADERS 一一对应，null 输出空串不伪造数值，手机号为明文）。
     */
    public static List<String> toRow(OrderPO po) {
        return Arrays.asList(
                str(po.getOrderNo()),
                po.getCreateTime() == null ? "" : po.getCreateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                str(po.getOrderType()),
                str(po.getStatus()),
                str(po.getReceiverName()),
                str(po.getReceiverPhone()), // 明文（商家履约需要，与列表脱敏差异）
                str(po.getPayType()),
                str(po.getLogisticsType()),
                str(po.getLogisticsNo()),
                str(po.getStoreName()),
                str(po.getSkuName()),
                po.getUnitPrice() == null ? "" : po.getUnitPrice().toPlainString(),
                str(po.getQuantity()),
                po.getTotalPrice() == null ? "" : po.getTotalPrice().toPlainString());
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
