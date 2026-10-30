package com.ysh.order.dao;

import com.ysh.order.dao.po.BuyerOrderQueryPO;
import com.ysh.order.dao.po.BuyerStatusCountPO;
import com.ysh.order.dao.po.HourAmountPO;
import com.ysh.order.dao.po.OrderPO;
import com.ysh.order.dao.po.OrderQueryPO;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderMapper {

    int insert(OrderPO order);

    OrderPO selectById(@Param("id") Integer id);

    int updateStatus(@Param("id") Integer id,
                     @Param("status") String status,
                     @Param("payType") String payType);

    /** 发货专用：更新状态 + 物流单号 + 物流类型（shop-order-list.md 功能上下文 7.1.1） */
    int updateShipStatus(@Param("id") Integer id,
                         @Param("status") String status,
                         @Param("logisticsNo") String logisticsNo,
                         @Param("logisticsType") String logisticsType);

    /** 按 shopId 分状态计数（TDD 红阶段空签名，SQL 待补充） */
    int countByShopIdAndStatus(@Param("shopId") Integer shopId, @Param("status") String status);

    /** 进行中的退款申请单据总数（TDD 红阶段空签名，SQL 待补充） */
    int countOngoingRefundsByShopId(@Param("shopId") Integer shopId);

    // ---- 商品删除前校验 TDD 红阶段占位签名（shop-product-list.md 功能上下文 7.3.1，XML 待绿阶段补全）----

    /** 指定商品的订单条数（shopId 隔离；任意状态的订单都算「已产生订单」，含未支付 PENDING） */
    int countBySkuId(@Param("skuId") Integer skuId, @Param("shopId") Integer shopId);

    /** 按小时聚合已支付金额（TDD 红阶段空签名，SQL 待补充） */
    List<HourAmountPO> sumPaidAmountByHour(@Param("shopId") Integer shopId,
                                           @Param("dayStart") String dayStart,
                                           @Param("dayEnd") String dayEnd);

    /** 今日支付订单数（TDD 红阶段空签名，SQL 待补充） */
    int countPaidOrders(@Param("shopId") Integer shopId,
                        @Param("dayStart") String dayStart,
                        @Param("dayEnd") String dayEnd);

    /** 今日支付客户数（distinct 买家）（TDD 红阶段空签名，SQL 待补充） */
    int countDistinctPaidBuyers(@Param("shopId") Integer shopId,
                                @Param("dayStart") String dayStart,
                                @Param("dayEnd") String dayEnd);

    /** 今日总支付金额（TDD 红阶段空签名，SQL 待补充） */
    BigDecimal sumPaidAmount(@Param("shopId") Integer shopId,
                             @Param("dayStart") String dayStart,
                             @Param("dayEnd") String dayEnd);

    /** 今日退款总金额（TDD 红阶段空签名，SQL 待补充） */
    BigDecimal sumRefundAmount(@Param("shopId") Integer shopId,
                               @Param("dayStart") String dayStart,
                               @Param("dayEnd") String dayEnd);

    // ---- 登录态强制隔离 TDD 红阶段占位签名（shop-order-list.md 功能上下文 2.3，XML 待绿阶段补全）----

    /** 按 orderId + 登录 shopId 查询（跨店返回 null） */
    OrderPO selectByIdAndShopId(@Param("id") Integer id, @Param("shopId") Integer shopId);

    /** 按 orderId + 登录 shopId 更新状态（跨店返回 0 行） */
    int updateStatusByIdAndShopId(@Param("id") Integer id,
                                  @Param("status") String status,
                                  @Param("shopId") Integer shopId);

    /** 按 orderId + 登录买家 username 查询（他人订单/已删除订单返回 null） */
    OrderPO selectByIdAndBuyerUsername(@Param("id") Integer id,
                                       @Param("buyerUsername") String buyerUsername);

    /** 买家逻辑删除（仅本人未删除订单，返回受影响行数） */
    int softDeleteByBuyer(@Param("id") Integer id, @Param("buyerUsername") String buyerUsername);

    /** 买家收藏订单（buyer_fav=1，仅本人未删除订单，返回受影响行数） */
    int favByBuyer(@Param("id") Integer id, @Param("buyerUsername") String buyerUsername);

    /** 买家取消收藏订单（buyer_fav=0，仅本人未删除订单，返回受影响行数，幂等） */
    int unfavByBuyer(@Param("id") Integer id, @Param("buyerUsername") String buyerUsername);

    // ---- 商家端订单列表分页 + 多维筛选 TDD 红阶段占位签名（shop-order-list.md 功能上下文 5.1，XML 待绿阶段补全）----

    /** 动态条件分页查询：shop_id 恒取自 filter.shopId，排序 create_time DESC, id DESC */
    List<OrderPO> selectPage(@Param("filter") OrderQueryPO filter,
                             @Param("offset") int offset,
                             @Param("limit") int limit);

    /** 与 selectPage 同源的筛选计数（双断言防漂移） */
    int countByFilter(@Param("filter") OrderQueryPO filter);

    // ---- 买家端订单列表 + 状态计数 TDD 红阶段占位签名（shop-order-list.md 功能上下文 10.1，XML 待绿阶段补全）----

    /**
     * 买家分页查询：buyer_username 恒置 + buyer_deleted=0 恒置；status 可选等值；order_no 精确；
     * 排序 create_time DESC, id DESC。
     */
    List<OrderPO> selectBuyerPage(@Param("filter") BuyerOrderQueryPO filter,
                                  @Param("offset") int offset,
                                  @Param("limit") int limit);

    /** 与 selectBuyerPage 同源的筛选计数（双断言防漂移） */
    int countBuyerByFilter(@Param("filter") BuyerOrderQueryPO filter);

    /**
     * 买家订单状态计数：GROUP BY status 且排除 buyer_deleted=1；无单状态返回 null count（Service 归一化为 0）。
     */
    List<BuyerStatusCountPO> countBuyerStatuses(@Param("buyerUsername") String buyerUsername);
}
