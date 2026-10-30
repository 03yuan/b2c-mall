package com.ysh.b2cmall.product.web.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * C 端商品卡片 VO（mall-category-product-list.md 功能上下文 2.3.2）：
 * 每张卡片只含 skuId、缩略图（images 首图）、名称（全名）、销售价、前台销量（合成值）。
 * 不暴露库存、注水分项、配送方式、商品类型、状态、市场价等商家字段（字段白名单，2.3.3）。
 */
@Data
public class MallProductItemVO {

    /** 商品 ID（供前端构造详情跳转链接） */
    private Integer skuId;

    /** 缩略图（images 第一张图；无图返回 null 不伪造） */
    private String thumbnail;

    /** 商品名称（全名，不截断） */
    private String name;

    /** 销售价（数值，单位元，原样返回） */
    private BigDecimal price;

    /** 前台销量（sales + fake_sales，fake_sales 为 null 按 0） */
    private Integer displaySales;
}
