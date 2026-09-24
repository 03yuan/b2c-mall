package com.ysh.b2cmall.shop.dao.mapper;

import com.ysh.b2cmall.shop.dao.po.ShopPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ShopMapper {

    Integer registerShop(ShopPO shopPO);

    int exist(@Param("shopName") String shopName);

    ShopPO selectById(@Param("id") Integer id);

    ShopPO findByShopNameAndAccount(@Param("shopName") String shopName,
                                    @Param("adminAccount") String adminAccount);
}
