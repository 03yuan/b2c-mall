package com.ysh.b2cmall.product.dao.mapper;

import com.ysh.b2cmall.product.dao.po.SkuLogPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SkuLogMapper {

    Integer insert(SkuLogPO po);
}
