package com.ysh.b2cmall.product.dao.po;

import lombok.Data;

import java.util.Date;

/**
 * 商品类目实体（平台级，所有店铺共用）
 */
@Data
public class CategoryPO {

    private Integer id;
    private String name;          // 类目名称
    private Integer parentId;     // 父类目ID（0为顶级）
    private Integer sort;         // 排序
    private Integer status;       // 1-正常 0-禁用
    private Date createdAt;
    private Date updatedAt;
}
