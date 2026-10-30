package com.ysh.b2cmall.product.web.request;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 批量设置请求体（shop-product-list.md 功能上下文 4.2）：
 * skuIds 勾选的商品 ID 列表；action 操作类型；value 该操作的目标值（字符串表达）。
 */
@Data
public class BatchUpdateRequest {

    /** 勾选的商品 ID 列表（不得为空） */
    @NotEmpty(message = "请选择要设置的商品")
    private List<Integer> skuIds;

    /** 操作类型：status / deliveryMethods / categoryId / sort / stock / storeStock */
    @NotBlank(message = "操作类型不能为空")
    private String action;

    /** 目标值（统一设为固定值，非增减；可为空由 Service 按操作类型校验） */
    private String value;
}
