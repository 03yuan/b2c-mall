package com.ysh.b2cmall.product.service;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 批量设置结果（shop-product-list.md 功能上下文 4.1）：
 * affected = 本店实际生效条数，skipped = 未生效条数（含混入的他店 skuId）。
 */
@Data
@AllArgsConstructor
public class BatchUpdateResult {

    /** 生效条数 */
    private int affected;

    /** 跳过条数（他店 id 等） */
    private int skipped;
}
