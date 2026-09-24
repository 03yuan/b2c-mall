package com.ysh.b2cmall.product.dao.mapper;

import com.ysh.b2cmall.product.dao.po.SkuPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SkuMapper {

    Integer insert(SkuPO po);

    SkuPO selectById(@Param("id") Integer id);

    int updateStatus(@Param("id") Integer id, @Param("status") Integer status);
}
