package com.ysh.b2cmall.user.dao.mapper;

import com.ysh.b2cmall.user.dao.po.UserPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {

    UserPO selectByUsername(@Param("username") String username);

    Integer insert(UserPO userPO);
}
