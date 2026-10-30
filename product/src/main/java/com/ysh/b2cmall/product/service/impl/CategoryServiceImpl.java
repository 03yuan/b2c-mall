package com.ysh.b2cmall.product.service.impl;

import com.ysh.b2cmall.product.dao.mapper.CategoryMapper;
import com.ysh.b2cmall.product.dao.po.CategoryPO;
import com.ysh.b2cmall.product.service.CategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CategoryServiceImpl implements CategoryService {

    @Autowired
    private CategoryMapper categoryMapper;

    // ---- 商品分类下拉（功能上下文 3.3）：shopId 恒取登录上下文并透传 DAO ----

    @Override
    public List<CategoryPO> listEnabledCategories(Integer shopId) {
        return categoryMapper.selectEnabledByShopId(shopId);
    }

    // ---- C 端分类导航（功能上下文 1.2）：公开免登录，跨店聚合 ----

    @Override
    public List<String> listMallTopLevelNames() {
        return categoryMapper.selectMallTopLevelNames();
    }
}
