package com.ysh.order.dao;

import com.ysh.order.dao.po.OrderPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderMapper {

    int insert(OrderPO order);

    OrderPO selectById(@Param("id") Integer id);

    int updateStatus(@Param("id") Integer id,
                     @Param("status") String status,
                     @Param("payType") String payType);
}
