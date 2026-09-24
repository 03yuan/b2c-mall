package com.ysh.b2cmall.product.dao.mapper;

import com.ysh.b2cmall.product.dao.po.CategoryPO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface CategoryMapper {

    /** 按ID查询正常状态的类目（平台级，不区分店铺） */
    CategoryPO selectById(Integer id);

    /** 查询所有正常状态的类目（供商家选择） */
    List<CategoryPO> selectAll();
}
