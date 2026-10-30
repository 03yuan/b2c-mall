package com.ysh.b2cmall.product.service;

import com.ysh.b2cmall.product.dao.po.CategoryPO;

import java.util.List;

public interface CategoryService {

    /**
     * 商品分类下拉：当前店铺 status=1 的类目，按 sort 升序（shopId 恒取登录上下文）。
     */
    List<CategoryPO> listEnabledCategories(Integer shopId);

    /**
     * C 端分类导航：全平台一级分类名（跨店聚合、按名去重、排序稳定，公开免登录）。
     */
    List<String> listMallTopLevelNames();
}
