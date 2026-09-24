package com.ysh.order.web.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;

@Data
public class CreateOrderRequest {

    @NotNull(message = "商品ID不能为空")
    private Integer skuId;

    @NotBlank(message = "商品名称不能为空")
    private String skuName;

    @NotNull(message = "购买数量不能为空")
    @Positive(message = "购买数量必须大于0")
    private Integer quantity;

    @NotNull(message = "单价不能为空")
    @Positive(message = "单价必须大于0")
    private BigDecimal price;

    @NotBlank(message = "收货人不能为空")
    private String receiverName;

    @NotBlank(message = "收货电话不能为空")
    private String receiverPhone;

    @NotBlank(message = "收货地址不能为空")
    private String receiverAddress;
}
