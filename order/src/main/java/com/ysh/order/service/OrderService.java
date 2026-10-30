package com.ysh.order.service;

import com.ysh.order.dao.po.BuyerOrderQueryPO;
import com.ysh.order.dao.po.OrderPO;
import com.ysh.order.dao.po.OrderPageResult;
import com.ysh.order.dao.po.OrderQueryPO;
import com.ysh.order.web.request.BatchShipRequest;
import com.ysh.order.web.vo.BatchShipResult;
import com.ysh.order.web.vo.BuyerContactVO;
import com.ysh.order.web.vo.BuyerOrderPageVO;
import com.ysh.order.web.vo.BuyerStatusCountsVO;
import com.ysh.order.web.vo.OrderDetailVO;
import com.ysh.order.web.vo.OrderRowVO;
import com.ysh.order.web.vo.RefundInfoVO;

import java.util.List;
import java.util.Map;

public interface OrderService {

    /** 下单 */
    Map<String, Object> createOrder(Integer shopId, Integer employeeId,
                                     Integer skuId, String skuName,
                                     Integer quantity, java.math.BigDecimal price,
                                     String receiverName, String receiverPhone, String receiverAddress,
                                     String shopName, String skuImage, String shopPhone,
                                     String buyerUsername);

    /** 支付（策略模式选支付方式，状态模式流转） */
    void pay(Integer orderId, String payType, Integer operatorId);

    /** 发货（状态模式流转） */
    void ship(Integer orderId, String logisticsNo, String logisticsType,
              Integer shopId, Integer operatorId);

    /** 确认收货（状态模式流转） */
    void confirm(Integer orderId, Integer operatorId);

    /** 取消订单（仅 PENDING -> FAILED，写 CANCEL 日志） */
    void cancel(Integer orderId, Integer operatorId);

    /** 退款申请（发 REFUND_APPLY 事件） */
    void refundApply(Integer orderId, Integer operatorId);

    /** 查询订单 */
    OrderPO getOrder(Integer orderId);

    /** 指定商品的订单条数（shop-product-list.md 功能上下文 7.3.1）：shopId 隔离，任意状态都算「已产生订单」 */
    int countBySkuId(Integer skuId, Integer shopId);

    /** 商家端订单列表分页查询（shop-order-list.md 功能上下文 5.2）：当前页行 + 条件命中总数 */
    OrderPageResult pageOrders(OrderQueryPO filter);

    /** 批量发货（shop-order-list.md 功能上下文 7.2）：逐条独立处理，失败汇总，好单不连坐 */
    BatchShipResult batchShip(List<BatchShipRequest.Item> items, Integer shopId, Integer operatorId);

    /** 导出订单数据组装（shop-order-list.md 功能上下文 8.1）：复用列表筛选取全量，排序与列表一致 */
    List<OrderRowVO> exportOrders(OrderQueryPO filter);

    /** 导出订单明文数据（shop-order-list.md 功能上下文 8.2）：复用列表筛选取全量，手机号明文（供 Excel 导出） */
    List<OrderPO> exportList(OrderQueryPO filter);

    /** 商家端订单详情（shop-order-list.md 功能上下文 9.1）：完整明细 + 状态流转日志，shopId 隔离 */
    OrderDetailVO getOrderDetail(Integer orderId, Integer shopId);

    /** 联系买家（shop-order-list.md 功能上下文 9.2）：返回买家账号 + 脱敏手机号，shopId 隔离 */
    BuyerContactVO getBuyerContact(Integer orderId, Integer shopId);

    /** 退款信息（shop-order-list.md 功能上下文 9.3）：有单据返回详情，无单据返回 null，shopId 隔离 */
    RefundInfoVO getRefundInfo(Integer orderId, Integer shopId);

    /** 买家端订单列表分页（shop-order-list.md 功能上下文 10.2）：buyer_username 隔离 + 卡片 VO 组装 */
    BuyerOrderPageVO pageBuyerOrders(BuyerOrderQueryPO filter);

    /** 买家端订单状态计数（shop-order-list.md 功能上下文 10.2）：固定五 key，无单状态为 0 */
    BuyerStatusCountsVO buyerStatusCounts(String buyerUsername);

    /** 买家端订单详情（shop-order-list.md 功能上下文 11.1.1）：复用 DetailVO 组装，buyer_username 隔离 */
    OrderDetailVO getBuyerOrderDetail(Integer orderId, String buyerUsername);

    /** 买家逻辑删除订单（shop-order-list.md 功能上下文 11.1.2）：幂等，buyer_username 隔离 */
    void deleteByBuyer(Integer orderId, String buyerUsername);

    /** 买家取消订单（shop-order-list.md 功能上下文 11.1.3）：仅 PENDING -> FAILED，buyer_username 隔离 */
    void cancelByBuyer(Integer orderId, String buyerUsername);

    /** 买家收藏订单（mall-order-manage.md 功能上下文 6.2.1）：本人未删 -> fav=1，他人/不存在/已删 -> 404 */
    void favByBuyer(Integer orderId, String buyerUsername);

    /** 买家取消收藏订单（mall-order-manage.md 功能上下文 6.2.2）：本人未删 -> fav=0，幂等，他人/不存在/已删 -> 404 */
    void unfavByBuyer(Integer orderId, String buyerUsername);

    /** 买家去支付（mall-order-manage.md 功能上下文 7.1.2）：本人 PENDING -> doPay，他人/不存在/已删 -> 404 */
    void payByBuyer(Integer orderId, String payType, String buyerUsername);

    /** 买家确认收货（mall-order-manage.md 功能上下文 8.1.1）：本人 SHIPPED -> doConfirm，他人/不存在/已删 -> 404 */
    void confirmByBuyer(Integer orderId, String buyerUsername);
}
