package com.ysh.b2cmall.product.dao.mapper;

import com.ysh.b2cmall.product.dao.po.CategoryPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CategoryMapper {

    /** 按ID查询正常状态的类目（平台级，不区分店铺） */
    CategoryPO selectById(Integer id);

    /** 查询所有正常状态的类目（供商家选择） */
    List<CategoryPO> selectAll();

    // ---- 商品分类下拉（功能上下文 3.3）----

    /** 按店铺查询启用（status=1）类目，按 sort 升序（SQL 必须恒带 shop_id 条件） */
    List<CategoryPO> selectEnabledByShopId(@Param("shopId") Integer shopId);

    // ---- C 端分类导航（功能上下文 1.1）----

    /** 全平台一级分类名（parent_id=0 AND status=1，跨店聚合、按名去重，排序稳定） */
    List<String> selectMallTopLevelNames();
}
