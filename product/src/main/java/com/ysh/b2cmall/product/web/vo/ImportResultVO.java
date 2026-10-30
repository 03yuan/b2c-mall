package com.ysh.b2cmall.product.web.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 商品导入结果响应 VO（shop-product-list.md 功能上下文 6.2.2）：
 * inserted/updated/errors（行号+原因）三段，供前端提示哪行失败。
 */
@Data
@AllArgsConstructor
public class ImportResultVO {

    /** 新增条数 */
    private int inserted;

    /** 更新条数 */
    private int updated;

    /** 错误行（「第N行：原因」），空列表表示全部成功 */
    private List<String> errors;
}
