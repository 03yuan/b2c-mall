package com.ysh.b2cmall.employee.dao.mapper;

import com.ysh.b2cmall.employee.dao.po.EmployeePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface EmployeeMapper {

    Integer save(EmployeePO employeePO);

    EmployeePO selectById(@Param("id") Integer id);

    List<EmployeePO> selectByShopId(@Param("shopId") Integer shopId);

    EmployeePO selectByUsernameAndShopId(@Param("username") String username, @Param("shopId") Integer shopId);

    int updateStatus(@Param("shopId") Integer shopId, @Param("status") Integer status);

    /**
     * 用户状态观察者使用：更新 last_login_time 为当前时间，login_count +1。
     */
    int updateLoginInfo(@Param("id") Integer id);

    /**
     * 审计日志观察者使用：写 tb_login_logs。
     * 用 @Param 而非新建 PO 类，减少新增类数量。
     */
    int saveLoginLog(@Param("userId") Integer userId,
                     @Param("ip") String ip,
                     @Param("device") String device,
                     @Param("location") String location,
                     @Param("userAgent") String userAgent,
                     @Param("status") Integer status);
}
