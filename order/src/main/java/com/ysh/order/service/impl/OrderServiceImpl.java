package com.ysh.order.service.impl;

import com.ysh.order.bean.CurrentEmployeeBean;
import com.ysh.order.component.RequestHolderComponent;
import com.ysh.order.dao.OrderLogMapper;
import com.ysh.order.dao.OrderMapper;
import com.ysh.order.dao.RefundMapper;
import com.ysh.order.dao.po.BuyerOrderQueryPO;
import com.ysh.order.dao.po.BuyerStatusCountPO;
import com.ysh.order.dao.po.OrderLogPO;
import com.ysh.order.dao.po.OrderPO;
import com.ysh.order.dao.po.OrderPageResult;
import com.ysh.order.dao.po.OrderQueryPO;
import com.ysh.order.dao.po.RefundPO;
import com.ysh.order.enums.OrderStatusEnum;
import com.ysh.order.enums.PayTypeEnum;
import com.ysh.order.mq.OrderEventPublisher;
import com.ysh.order.service.OrderService;
import com.ysh.order.service.PayStrategyService;
import com.ysh.order.statemachine.OrderState;
import com.ysh.order.statemachine.OrderStateFactory;
import com.ysh.order.web.request.BatchShipRequest;
import com.ysh.order.web.vo.BatchShipResult;
import com.ysh.order.web.vo.BuyerContactVO;
import com.ysh.order.web.vo.BuyerOrderCardVO;
import com.ysh.order.web.vo.BuyerOrderItemVO;
import com.ysh.order.web.vo.BuyerOrderPageVO;
import com.ysh.order.web.vo.BuyerStatusCountsVO;
import com.ysh.order.web.vo.OrderDetailVO;
import com.ysh.order.web.vo.OrderItemVO;
import com.ysh.order.web.vo.OrderRowVO;
import com.ysh.order.web.vo.RefundInfoVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderLogMapper orderLogMapper;
    @Autowired
    private RefundMapper refundMapper;
    @Autowired
    private OrderStateFactory stateFactory;
    @Autowired
    private RequestHolderComponent requestHolder;
    @Autowired
    private List<PayStrategyService> payStrategies;
    @Autowired
    private OrderEventPublisher orderEventPublisher;

    @Override
    public Map<String, Object> createOrder(Integer shopId, Integer employeeId,
                                           Integer skuId, String skuName,
                                           Integer quantity, BigDecimal price,
                                           String receiverName, String receiverPhone, String receiverAddress,
                                           String shopName, String skuImage, String shopPhone,
                                           String buyerUsername) {
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("购买数量必须大于0");
        }
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("单价必须大于0");
        }

        BigDecimal totalPrice = price.multiply(BigDecimal.valueOf(quantity));

        OrderPO order = new OrderPO();
        order.setOrderNo(generateOrderNo());
        order.setShopId(shopId);
        order.setSkuId(skuId);
        order.setSkuName(skuName);
        order.setQuantity(quantity);
        order.setTotalPrice(totalPrice);
        order.setStatus(OrderStatusEnum.PENDING.name());
        order.setReceiverName(receiverName);
        order.setReceiverPhone(receiverPhone);
        order.setReceiverAddress(receiverAddress);
        order.setBuyerUsername(buyerUsername);
        order.setShopName(emptyToNull(shopName));
        order.setSkuImage(emptyToNull(skuImage));
        order.setShopPhone(emptyToNull(shopPhone));
        order.setUnitPrice(price);

        orderMapper.insert(order);

        Map<String, Object> data = new HashMap<>();
        data.put("orderId", order.getId());
        data.put("orderNo", order.getOrderNo());
        data.put("totalPrice", order.getTotalPrice());
        data.put("status", order.getStatus());
        return data;
    }

    /** 空串归一化为 null（5.2.2：新字段 null/空串均落库 null，向后兼容） */
    private String emptyToNull(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }

    @Override
    public void pay(Integer orderId, String payType, Integer operatorId) {
        OrderPO order = getOrder(orderId);
        doPay(order, payType, operatorId);
    }

    /**
     * 支付主体（mall-order-manage.md 功能上下文 7.1.1）：策略选型 + 状态流转 + 事件发布。
     * 商家 pay 与买家 payByBuyer 共用，杜绝 pay_type/pay_time/事件口径分叉。
     */
    private void doPay(OrderPO order, String payType, Integer operatorId) {
        // 1. 策略模式：选择支付方式
        PayTypeEnum payTypeEnum = PayTypeEnum.fromString(payType);
        PayStrategyService strategy = payStrategies.stream()
                .filter(s -> s.getPayType().equalsIgnoreCase(payTypeEnum.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的支付方式: " + payType));

        // 2. 执行支付
        boolean success = strategy.pay(order.getOrderNo(), order.getTotalPrice());
        if (!success) {
            throw new RuntimeException("支付失败");
        }

        // 3. 状态模式：流转订单状态 PENDING -> PAID
        transitionState(order, "PAY", strategy.getPayType(), operatorId);
        // 4. 发布支付成功事件（tag：PAY_SUCCESS）
        orderEventPublisher.publishPaySuccess(order);
        log.info("订单支付成功：orderId={}, payType={}", order.getId(), payType);
    }

    @Override
    public void ship(Integer orderId, String logisticsNo, String logisticsType,
                     Integer shopId, Integer operatorId) {
        // 参数校验（7.1.2）：物流单号非空、物流类型在枚举内
        if (logisticsNo == null || logisticsNo.trim().isEmpty()) {
            throw new IllegalArgumentException("物流单号不能为空");
        }
        String normalizedType = logisticsType == null ? null : logisticsType.trim().toUpperCase();
        if (!"EXPRESS".equals(normalizedType) && !"CITY".equals(normalizedType)) {
            throw new IllegalArgumentException("物流类型非法（仅支持 EXPRESS / CITY）");
        }
        // shopId 强制隔离（7.1.2）：shopId 为空不得发货
        if (shopId == null) {
            throw new IllegalArgumentException("店铺ID不能为空");
        }

        // 查询订单并校验归属（7.1.2 他店订单不生效）
        OrderPO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在: " + orderId);
        }
        if (!shopId.equals(order.getShopId())) {
            throw new IllegalArgumentException("订单不存在: " + orderId);
        }

        // 状态模式：PAID -> SHIPPED（仅 PAID 可发货，状态机保障；重复发货抛 IllegalStateException）
        transitionState(order, "SHIP", null, operatorId, logisticsNo.trim(), normalizedType);
        log.info("订单发货成功：orderId={}, logisticsNo={}, logisticsType={}", orderId, logisticsNo, normalizedType);
    }

    @Override
    public void confirm(Integer orderId, Integer operatorId) {
        OrderPO order = getOrder(orderId);
        doConfirm(order, operatorId);
    }

    /**
     * 确认收货主体（mall-order-manage.md 功能上下文 8.1.1）：状态流转 + 事件发布。
     * 商家 confirm 与买家 confirmByBuyer 共用，杜绝状态机/事件口径分叉。
     */
    private void doConfirm(OrderPO order, Integer operatorId) {
        // 状态模式：SHIPPED -> COMPLETED
        transitionState(order, "CONFIRM", null, operatorId);
        // 发布确认收货事件（tag：CONFIRM_RECEIPT）
        orderEventPublisher.publishConfirmReceipt(order);
        log.info("订单签收成功：orderId={}", order.getId());
    }

    @Override
    public void cancel(Integer orderId, Integer operatorId) {
        OrderPO order = getOrder(orderId);

        // 状态模式：仅 PENDING -> FAILED（已付款及之后由状态类拒绝）
        transitionState(order, "CANCEL", null, operatorId);
        log.info("订单已取消：orderId={}", orderId);
    }

    @Override
    public void refundApply(Integer orderId, Integer operatorId) {
        OrderPO order = getOrder(orderId);
        // 发布退款申请事件（tag：REFUND_APPLY）
        orderEventPublisher.publishRefundApply(order);
        log.info("退款申请已受理：orderId={}", orderId);
    }

    @Override
    public OrderPO getOrder(Integer orderId) {
        OrderPO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在: " + orderId);
        }
        return order;
    }

    // ---- 商品删除前校验（功能上下文 7.3.1）：shopId 隔离，任意状态都算「已产生订单」----

    @Override
    public int countBySkuId(Integer skuId, Integer shopId) {
        return orderMapper.countBySkuId(skuId, shopId);
    }

    // ---- 商家端订单列表分页（shop-order-list.md 功能上下文 5.2 / 6.1）----

    /** 售后状态：无售后单据 */
    private static final String REFUND_NONE = "NONE";
    /** 售后状态：退款申请中 */
    private static final String REFUND_PROCESSING = "PROCESSING";

    @Override
    public OrderPageResult pageOrders(OrderQueryPO filter) {
        // 分页参数校验（page>=1、size 1~100），非法抛 IllegalArgumentException -> 400
        int offset = filter.resolveOffset();
        List<OrderPO> orders = orderMapper.selectPage(filter, offset, filter.getSize());
        int total = orderMapper.countByFilter(filter);
        if (orders == null || orders.isEmpty()) {
            // 空页不发起批量退款查询，也不伪造任何行
            return new OrderPageResult(Collections.emptyList(), total);
        }

        Map<Integer, String> refundStatusByOrderId = loadRefundStatus(orders);
        List<OrderRowVO> rows = new ArrayList<>(orders.size());
        for (OrderPO order : orders) {
            rows.add(buildRowVO(order, refundStatusByOrderId.getOrDefault(order.getId(), REFUND_NONE)));
        }
        return new OrderPageResult(rows, total);
    }

    /**
     * 批量发货（shop-order-list.md 功能上下文 7.2）：items 逐条独立处理，失败汇总，好单不连坐。
     * 单条复用 {@link #ship}：非待发货/他店/不存在/缺物流单号/非法物流类型 -> 该条计入失败（orderId+中文原因），
     * 好单照常发货，绝不因一条失败整批回滚。
     */
    @Override
    public BatchShipResult batchShip(List<BatchShipRequest.Item> items, Integer shopId, Integer operatorId) {
        BatchShipResult result = new BatchShipResult();
        int success = 0;
        for (BatchShipRequest.Item item : items) {
            try {
                ship(item.getOrderId(), item.getLogisticsNo(), item.getLogisticsType(), shopId, operatorId);
                success++;
            } catch (IllegalArgumentException | IllegalStateException e) {
                BatchShipResult.FailedItem failed = new BatchShipResult.FailedItem();
                failed.setOrderId(item.getOrderId());
                failed.setReason(e.getMessage());
                result.getFailed().add(failed);
            }
        }
        result.setSuccess(success);
        return result;
    }

    /**
     * 导出订单数据组装（shop-order-list.md 功能上下文 8.1）：复用列表筛选构建器取全量（不分页），
     * 排序与列表一致（create_time DESC, id DESC，由 selectPage 的 ORDER BY 保证），行组装复用 buildRowVO。
     */
    @Override
    public List<OrderRowVO> exportOrders(OrderQueryPO filter) {
        // 先按同源 countByFilter 拿到命中总数，再一次性取回全量（不分页，不按默认 size 截断）
        int total = orderMapper.countByFilter(filter);
        List<OrderPO> orders = orderMapper.selectPage(filter, 0, Math.max(total, 1));
        if (orders == null || orders.isEmpty()) {
            // 空结果返回空列表，不伪造任何行
            return Collections.emptyList();
        }
        Map<Integer, String> refundStatusByOrderId = loadRefundStatus(orders);
        List<OrderRowVO> rows = new ArrayList<>(orders.size());
        for (OrderPO order : orders) {
            rows.add(buildRowVO(order, refundStatusByOrderId.getOrDefault(order.getId(), REFUND_NONE)));
        }
        return rows;
    }

    /**
     * 导出订单明文数据（shop-order-list.md 功能上下文 8.2）：复用列表筛选取全量，手机号明文（供 Excel 导出）。
     */
    @Override
    public List<OrderPO> exportList(OrderQueryPO filter) {
        int total = orderMapper.countByFilter(filter);
        List<OrderPO> orders = orderMapper.selectPage(filter, 0, Math.max(total, 1));
        return orders == null ? Collections.emptyList() : orders;
    }

    /**
     * 商家端订单详情（shop-order-list.md 功能上下文 9.1）：完整明细 + 状态流转日志，shopId 隔离。
     */
    @Override
    public OrderDetailVO getOrderDetail(Integer orderId, Integer shopId) {
        // shopId 强制隔离：跨店/不存在返回 null -> 404
        OrderPO order = orderMapper.selectByIdAndShopId(orderId, shopId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在: " + orderId);
        }

        // 售后状态派生（单条，复用批量查询）
        String refundStatus = REFUND_NONE;
        List<RefundPO> refunds = refundMapper.selectByOrderIds(Collections.singletonList(orderId));
        if (refunds != null && !refunds.isEmpty()) {
            refundStatus = refunds.get(0).getStatus();
        }

        // 组装 DetailVO：行 VO 全字段 + 收货地址/物流/支付时间 + 状态流转日志
        OrderRowVO row = buildRowVO(order, refundStatus);
        OrderDetailVO detail = new OrderDetailVO();
        BeanUtils.copyProperties(row, detail);
        detail.setReceiverAddress(order.getReceiverAddress());
        detail.setLogisticsNo(order.getLogisticsNo());
        detail.setLogisticsType(order.getLogisticsType());
        detail.setPayTime(parsePayTime(order.getPayTime()));
        detail.setLogs(orderLogMapper.selectByOrderId(orderId));
        return detail;
    }

    /**
     * 联系买家（shop-order-list.md 功能上下文 9.2）：返回买家账号 + 脱敏手机号，shopId 隔离。
     */
    @Override
    public BuyerContactVO getBuyerContact(Integer orderId, Integer shopId) {
        OrderPO order = orderMapper.selectByIdAndShopId(orderId, shopId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在: " + orderId);
        }
        BuyerContactVO vo = new BuyerContactVO();
        // 历史单未写入买家账号 -> 空串而非 null/500
        vo.setBuyerUsername(order.getBuyerUsername() == null ? "" : order.getBuyerUsername());
        // 脱敏手机号（隐私保护，不得明文）
        vo.setMaskedPhone(maskPhone(order.getReceiverPhone()));
        return vo;
    }

    /**
     * 退款信息（shop-order-list.md 功能上下文 9.3）：有单据返回详情，无单据返回 null，shopId 隔离。
     */
    @Override
    public RefundInfoVO getRefundInfo(Integer orderId, Integer shopId) {
        // shopId 强制隔离：跨店/不存在 -> IllegalArgumentException -> 404
        OrderPO order = orderMapper.selectByIdAndShopId(orderId, shopId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在: " + orderId);
        }

        // 有售后单据则取最新一条（DAO 按 id DESC 返回，首条即最新）；无单据 -> null（暂无售后）
        List<RefundPO> refunds = refundMapper.selectByOrderIds(Collections.singletonList(orderId));
        if (refunds == null || refunds.isEmpty()) {
            return null;
        }
        RefundPO refund = refunds.get(0);
        RefundInfoVO vo = new RefundInfoVO();
        vo.setStatus(refund.getStatus());
        vo.setAmount(refund.getAmount());
        vo.setCreateTime(refund.getCreateTime());
        return vo;
    }

    /**
     * 买家端订单列表分页（shop-order-list.md 功能上下文 10.2）：buyer_username 隔离 + 卡片 VO 组装。
     */
    @Override
    public BuyerOrderPageVO pageBuyerOrders(BuyerOrderQueryPO filter) {
        int offset = filter.resolveOffset();
        List<OrderPO> orders = orderMapper.selectBuyerPage(filter, offset, filter.getSize());
        int total = orderMapper.countBuyerByFilter(filter);
        if (orders == null || orders.isEmpty()) {
            return new BuyerOrderPageVO(Collections.emptyList(), total);
        }
        List<BuyerOrderCardVO> rows = new ArrayList<>(orders.size());
        for (OrderPO order : orders) {
            rows.add(buildBuyerCard(order));
        }
        return new BuyerOrderPageVO(rows, total);
    }

    /**
     * 买家端订单状态计数（shop-order-list.md 功能上下文 10.2）：固定五 key，无单状态为 0。
     */
    @Override
    public BuyerStatusCountsVO buyerStatusCounts(String buyerUsername) {
        List<BuyerStatusCountPO> counts = orderMapper.countBuyerStatuses(buyerUsername);
        BuyerStatusCountsVO vo = new BuyerStatusCountsVO();
        if (counts == null) {
            return vo;
        }
        int all = 0;
        for (BuyerStatusCountPO row : counts) {
            int c = row.getCount() == null ? 0 : row.getCount();
            all += c;
            if (OrderStatusEnum.PENDING.name().equals(row.getStatus())) {
                vo.setPending(c);
            } else if (OrderStatusEnum.PAID.name().equals(row.getStatus())) {
                vo.setPaid(c);
            } else if (OrderStatusEnum.SHIPPED.name().equals(row.getStatus())) {
                vo.setShipped(c);
            } else if (OrderStatusEnum.COMPLETED.name().equals(row.getStatus())) {
                vo.setCompleted(c);
            }
        }
        vo.setAll(all);
        return vo;
    }

    /** 买家卡片 VO 组装：订单快照 + 商品行 + 状态中文文案，待发货（PAID）附带 payType */
    private BuyerOrderCardVO buildBuyerCard(OrderPO order) {
        BuyerOrderItemVO item = new BuyerOrderItemVO();
        item.setSkuImage(order.getSkuImage());
        item.setSkuName(order.getSkuName());
        item.setQuantity(order.getQuantity());
        item.setUnitPrice(order.getUnitPrice());

        BuyerOrderCardVO card = new BuyerOrderCardVO();
        card.setOrderId(order.getId());
        card.setOrderNo(order.getOrderNo());
        card.setShopName(order.getShopName());
        card.setCreateTime(order.getCreateTime());
        card.setStatus(order.getStatus());
        card.setStatusText(statusText(order.getStatus()));
        card.setPayType(OrderStatusEnum.PAID.name().equals(order.getStatus()) ? order.getPayType() : null);
        card.setItems(Collections.singletonList(item));
        card.setTotalPrice(order.getTotalPrice());
        card.setSkuId(order.getSkuId());
        card.setShopPhone(order.getShopPhone() == null ? "" : order.getShopPhone());
        card.setBuyerFav(order.getBuyerFav());
        return card;
    }

    /** 订单状态中文文案（买家卡片口径） */
    private static String statusText(String status) {
        if (status == null) {
            return "";
        }
        switch (status) {
            case "PENDING":
                return "待付款";
            case "PAID":
                return "待发货";
            case "SHIPPED":
                return "待收货";
            case "COMPLETED":
                return "已完成";
            case "FAILED":
                return "交易失败";
            case "GROUP_PENDING":
                return "待成团";
            default:
                return status;
        }
    }

    /**
     * 买家端订单详情（shop-order-list.md 功能上下文 11.1.1）：复用 DetailVO 组装，buyer_username 隔离。
     */
    @Override
    public OrderDetailVO getBuyerOrderDetail(Integer orderId, String buyerUsername) {
        // buyer_username 强制隔离：他人/不存在/已删 -> null -> 抛"订单不存在"
        OrderPO order = orderMapper.selectByIdAndBuyerUsername(orderId, buyerUsername);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在: " + orderId);
        }

        String refundStatus = REFUND_NONE;
        List<RefundPO> refunds = refundMapper.selectByOrderIds(Collections.singletonList(orderId));
        if (refunds != null && !refunds.isEmpty()) {
            refundStatus = refunds.get(0).getStatus();
        }

        OrderRowVO row = buildRowVO(order, refundStatus);
        OrderDetailVO detail = new OrderDetailVO();
        BeanUtils.copyProperties(row, detail);
        detail.setReceiverAddress(order.getReceiverAddress());
        detail.setLogisticsNo(order.getLogisticsNo());
        detail.setLogisticsType(order.getLogisticsType());
        detail.setPayTime(parsePayTime(order.getPayTime()));
        detail.setLogs(orderLogMapper.selectByOrderId(orderId));
        return detail;
    }

    /**
     * 买家逻辑删除订单（shop-order-list.md 功能上下文 11.1.2）：幂等，buyer_username 隔离。
     */
    @Override
    public void deleteByBuyer(Integer orderId, String buyerUsername) {
        // 用不带 buyer_deleted 条件的 selectById 校验存在性与归属，避免本人已删订单被误判为「不存在」
        OrderPO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在: " + orderId);
        }
        if (!buyerUsername.equals(order.getBuyerUsername())) {
            throw new IllegalArgumentException("订单不存在: " + orderId);
        }
        if (order.getBuyerDeleted() != null && order.getBuyerDeleted() == 1) {
            // 本人已删订单：幂等成功，不再重复删除
            return;
        }
        // 逻辑删除：buyer_deleted=1（重复删除幂等，他人订单零改动）
        orderMapper.softDeleteByBuyer(orderId, buyerUsername);
    }

    /**
     * 买家取消订单（shop-order-list.md 功能上下文 11.1.3）：仅 PENDING -> FAILED，buyer_username 隔离。
     */
    @Override
    public void cancelByBuyer(Integer orderId, String buyerUsername) {
        OrderPO order = orderMapper.selectByIdAndBuyerUsername(orderId, buyerUsername);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在: " + orderId);
        }
        // 状态机：仅 PENDING -> FAILED（已付款及之后由状态类抛 IllegalStateException），写 CANCEL 日志
        transitionState(order, "CANCEL", null, null);
    }

    /**
     * 买家收藏订单（mall-order-manage.md 功能上下文 6.2.1）：本人未删 -> fav=1，他人/不存在/已删 -> 404。
     * TDD 红阶段空实现，绿阶段补归属校验 + favByBuyer 落库。
     */
    @Override
    public void favByBuyer(Integer orderId, String buyerUsername) {
        OrderPO order = orderMapper.selectByIdAndBuyerUsername(orderId, buyerUsername);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在: " + orderId);
        }
        orderMapper.favByBuyer(orderId, buyerUsername);
    }

    /**
     * 买家取消收藏订单（mall-order-manage.md 功能上下文 6.2.2）：本人未删 -> fav=0，幂等，他人/不存在/已删 -> 404。
     */
    @Override
    public void unfavByBuyer(Integer orderId, String buyerUsername) {
        OrderPO order = orderMapper.selectByIdAndBuyerUsername(orderId, buyerUsername);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在: " + orderId);
        }
        orderMapper.unfavByBuyer(orderId, buyerUsername);
    }

    /**
     * 买家去支付订单（mall-order-manage.md 功能上下文 7.1.2）：本人未删 PENDING -> doPay，他人/不存在/已删 -> 404。
     */
    @Override
    public void payByBuyer(Integer orderId, String payType, String buyerUsername) {
        OrderPO order = orderMapper.selectByIdAndBuyerUsername(orderId, buyerUsername);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在: " + orderId);
        }
        // operatorId 传买家语义（0），供写日志审计用
        doPay(order, payType, 0);
    }

    /**
     * 买家确认收货订单（mall-order-manage.md 功能上下文 8.1.1）：本人未删 SHIPPED -> doConfirm，他人/不存在/已删 -> 404。
     */
    @Override
    public void confirmByBuyer(Integer orderId, String buyerUsername) {
        OrderPO order = orderMapper.selectByIdAndBuyerUsername(orderId, buyerUsername);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在: " + orderId);
        }
        // operatorId 传买家语义（0），供写日志审计用
        doConfirm(order, 0);
    }

    /** 支付时间字符串 -> LocalDateTime（NULL/空 -> null，未支付订单无支付时间） */
    private static LocalDateTime parsePayTime(String payTime) {
        if (payTime == null || payTime.isEmpty()) {
            return null;
        }
        return LocalDateTime.parse(payTime, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * 售后状态批量派生：按本页 orderId IN 一次查回，避免逐行查询（N+1）。
     * DAO 按 id DESC 返回，同一订单多条退款单时首条即最新，putIfAbsent 保留首条。
     */
    private Map<Integer, String> loadRefundStatus(List<OrderPO> orders) {
        List<Integer> orderIds = new ArrayList<>(orders.size());
        for (OrderPO order : orders) {
            orderIds.add(order.getId());
        }
        Map<Integer, String> statusMap = new HashMap<>();
        List<RefundPO> refunds = refundMapper.selectByOrderIds(orderIds);
        if (refunds != null) {
            for (RefundPO refund : refunds) {
                statusMap.putIfAbsent(refund.getOrderId(), refund.getStatus());
            }
        }
        return statusMap;
    }

    /** 单行 VO 组装：items 数组、共 N 件、脱敏手机号、标签派生、原始枚举值 */
    private OrderRowVO buildRowVO(OrderPO order, String refundStatus) {
        OrderItemVO item = new OrderItemVO();
        item.setSkuImage(order.getSkuImage());
        item.setSkuName(order.getSkuName());
        item.setUnitPrice(order.getUnitPrice());
        item.setQuantity(order.getQuantity());
        item.setIsGift(order.getIsGift());

        OrderRowVO vo = new OrderRowVO();
        vo.setOrderId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setCreateTime(order.getCreateTime());
        vo.setStoreName(order.getStoreName());
        vo.setItems(Collections.singletonList(item));
        vo.setTotalPrice(order.getTotalPrice());
        // 共 N 件 = Σ items.quantity（当前一单一商品模型下等于 quantity）
        vo.setTotalQuantity(order.getQuantity() == null ? 0 : order.getQuantity());
        vo.setPayType(order.getPayType());
        vo.setBuyerUsername(order.getBuyerUsername() == null ? "" : order.getBuyerUsername());
        vo.setReceiverName(order.getReceiverName());
        vo.setReceiverPhoneMasked(maskPhone(order.getReceiverPhone()));
        vo.setStatus(order.getStatus());
        vo.setRefundStatus(refundStatus);
        vo.setTags(buildTags(order, refundStatus));
        return vo;
    }

    /** 商品标签派生：随字段变化，状态流转后不会残留旧标签 */
    private List<String> buildTags(OrderPO order, String refundStatus) {
        List<String> tags = new ArrayList<>();
        if (OrderStatusEnum.PAID.name().equals(order.getStatus())) {
            tags.add("待发货");
        }
        if (OrderStatusEnum.SHIPPED.name().equals(order.getStatus())) {
            tags.add("待收货");
        }
        if (REFUND_PROCESSING.equals(refundStatus)) {
            tags.add("退款申请中");
        }
        if ("CARD".equals(order.getOrderCategory())) {
            tags.add("电子卡券订单");
        }
        if ("CITY".equals(order.getLogisticsType())) {
            tags.add("同城配送");
        }
        if (order.getIsGift() != null && order.getIsGift() == 1) {
            tags.add("赠品");
        }
        return tags;
    }

    /**
     * 手机号脱敏（列表展示口径，导出为明文）：
     * 11 位 -> 前 3 后 4；4~10 位 -> 前 2 后 2；不足 4 位 -> 全掩码；null/空 -> 空串。
     */
    private static String maskPhone(String phone) {
        if (phone == null || phone.isEmpty()) {
            return "";
        }
        int len = phone.length();
        if (len == 11) {
            return phone.substring(0, 3) + "****" + phone.substring(7);
        }
        if (len >= 4) {
            return phone.substring(0, 2) + "****" + phone.substring(len - 2);
        }
        return "****";
    }

    /**
     * 状态流转通用方法
     * @param order 订单
     * @param event 事件名称（PAY/SHIP/CONFIRM/CANCEL）
     * @param payType 支付方式（仅PAY时传入，其他传null）
     * @param operatorId 操作人
     */
    private void transitionState(OrderPO order, String event, String payType, Integer operatorId) {
        transitionState(order, event, payType, operatorId, null, null);
    }

    private void transitionState(OrderPO order, String event, String payType, Integer operatorId,
                                  String logisticsNo, String logisticsType) {
        // 从DB状态恢复状态对象
        OrderState currentState = stateFactory.getState(order.getStatus());

        // 根据事件调用对应方法，状态类内部校验合法性（不合法会抛IllegalStateException）
        OrderState newState;
        switch (event) {
            case "PAY":
                newState = currentState.pay();
                break;
            case "SHIP":
                newState = currentState.ship();
                break;
            case "CONFIRM":
                newState = currentState.confirm();
                break;
            case "CANCEL":
                newState = currentState.cancel();
                break;
            default:
                throw new IllegalArgumentException("未知事件: " + event);
        }

        // 更新DB（发货走 updateShipStatus，其余走 updateStatus）
        if ("SHIP".equals(event)) {
            orderMapper.updateShipStatus(order.getId(), newState.getStatus().name(), logisticsNo, logisticsType);
        } else {
            orderMapper.updateStatus(order.getId(), newState.getStatus().name(), payType);
        }

        // 写日志
        OrderLogPO logEntry = new OrderLogPO();
        logEntry.setOrderId(order.getId());
        logEntry.setFromStatus(order.getStatus());
        logEntry.setToStatus(newState.getStatus().name());
        logEntry.setEvent(event);
        logEntry.setOperatorId(operatorId);
        logEntry.setRemark(event + ": " + order.getStatus() + " -> " + newState.getStatus().name());
        orderLogMapper.insert(logEntry);
    }

    /**
     * 生成订单号：年月日时分秒 + 6位随机数
     */
    private String generateOrderNo() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));
    }
}
