package com.ysh.b2cmall.product.web.excel;

import com.ysh.b2cmall.product.dao.po.SkuPO;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 商品 Excel 列定义（shop-product-list.md 功能上下文 5.2/6.x）：
 * 导出表头 = 导入模板的单一列定义来源，两端共用，防止漂移后导出文件无法导入。
 * 列顺序即 Excel 列顺序。
 */
public final class ProductExcelColumns {

    private ProductExcelColumns() {
    }

    /** 表头列（列表列 + 商品ID，商品ID 为导入识别新增/更新的依据） */
    public static final List<String> HEADERS = Collections.unmodifiableList(Arrays.asList(
            "商品ID", "商品名称", "市场价", "销售价", "商品销量", "注水销量",
            "仓库库存", "门店库存", "商品类型", "商品类别", "状态", "配送方式", "排序"));

    /** PO → Excel 数据行（列序与 HEADERS 一一对应，null 输出空串不伪造数值） */
    public static List<String> toRow(SkuPO po) {
        return Arrays.asList(
                str(po.getId()),
                str(po.getName()),
                po.getMarketPrice() == null ? "" : po.getMarketPrice().toPlainString(),
                po.getPrice() == null ? "" : po.getPrice().toPlainString(),
                str(po.getSales()),
                str(po.getFakeSales()),
                str(po.getStock()),
                str(po.getStoreStock()),
                str(po.getProductType()),
                str(po.getType()),
                str(po.getStatus()),
                str(po.getDeliveryMethods()),
                str(po.getSort()));
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}

