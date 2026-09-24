package com.ysh.order.dao;

import com.ysh.order.dao.po.OrderLogPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderLogMapper {

    int insert(OrderLogPO log);
}
