package com.ysh.b2cmall.shop.dao.mapper;

import com.ysh.b2cmall.shop.dao.po.ShopMessagePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ShopMessageMapper {

    int insert(ShopMessagePO message);

    List<ShopMessagePO> selectPageByShopId(@Param("shopId") Integer shopId,
                                           @Param("offset") int offset,
                                           @Param("limit") int limit);
}
