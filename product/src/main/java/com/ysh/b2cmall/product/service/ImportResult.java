package com.ysh.b2cmall.product.service;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 商品导入结果（shop-product-list.md 功能上下文 6.1/6.2）：
 * inserted = 新增条数，updated = 更新条数，errors = 「行号+原因」列表（逐行独立，不整批回滚）。
 */
@Data
@AllArgsConstructor
public class ImportResult {

    /** 新增条数 */
    private int inserted;

    /** 更新条数 */
    private int updated;

    /** 错误行（「第N行：原因」），空列表表示全部成功 */
    private List<String> errors;

    public static ImportResult empty() {
        return new ImportResult(0, 0, new ArrayList<>());
    }
}
