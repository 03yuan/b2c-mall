package com.ysh.b2cmall.shop.dao.mapper;

import com.ysh.b2cmall.shop.dao.po.MessagePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MessageMapper {

    int save(MessagePO messagePO);

    List<MessagePO> selectByShopId(@Param("shopId") Integer shopId);
}
