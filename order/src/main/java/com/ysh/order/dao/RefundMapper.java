package com.ysh.order.dao;

import com.ysh.order.dao.po.RefundPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RefundMapper {

    // ---- 行 VO 售后状态批量派生 TDD 红阶段占位签名（shop-order-list.md 功能上下文 6.1.4，XML 待绿阶段补全）----

    /**
     * 按订单 id 批量取售后单据（按 id DESC，同单多条时首条即最新），禁止逐行查询。
     */
    List<RefundPO> selectByOrderIds(@Param("orderIds") List<Integer> orderIds);
}
