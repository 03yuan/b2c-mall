package com.ysh.order.dao;

import com.ysh.order.dao.po.OrderLogPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OrderLogMapper {

    int insert(OrderLogPO log);

    /** 按订单 id 查询状态流转日志（按 id 升序，即时间升序，shop-order-list.md 功能上下文 9.1.1） */
    List<OrderLogPO> selectByOrderId(@Param("orderId") Integer orderId);
}
