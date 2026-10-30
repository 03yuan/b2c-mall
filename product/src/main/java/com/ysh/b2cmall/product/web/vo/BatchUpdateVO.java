package com.ysh.b2cmall.product.web.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 批量设置响应（shop-product-list.md 功能上下文 4.2）：
 * affected = 生效条数，skipped = 跳过条数（含混入的他店 skuId）。
 */
@Data
@AllArgsConstructor
public class BatchUpdateVO {

    private Integer affected;

    private Integer skipped;
}
