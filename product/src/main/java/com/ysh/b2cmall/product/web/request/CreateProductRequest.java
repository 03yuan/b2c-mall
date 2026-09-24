package com.ysh.b2cmall.product.web.request;

import lombok.Data;

import javax.validation.constraints.*;
import java.math.BigDecimal;

/**
 * 新建商品请求
 */
@Data
public class CreateProductRequest {

    @NotNull(message = "店铺ID不能为空")
    private Integer shopId;

    @NotNull(message = "类目ID不能为空")
    private Integer categoryId;

    @NotBlank(message = "商品名称不能为空")
    @Size(max = 100, message = "商品名称不能超过100字符")
    private String name;

    private String description;

    @NotNull(message = "价格不能为空")
    @DecimalMin(value = "0.01", message = "价格必须大于0")
    private BigDecimal price;

    /**
     * 库存：实物商品必填且>=0；虚拟商品可为空（无限库存）
     */
    private Integer stock;

    @NotNull(message = "商品类型不能为空")
    private Integer type;            // 1-实物 2-虚拟

    private String images;
    private String spec;
}
