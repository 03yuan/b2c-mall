package com.ysh.b2cmall.shop.mq;

/**
 * 订单事件类型与消息文本模板（集中定义，防模板漂移）。
 * 模板逐字固定，含订单编号与买家名，不得多空格/改措辞。
 */
public enum MessageTypeEnum {

    /** 支付成功 */
    PAY_SUCCESS {
        @Override
        public String buildContent(Integer orderId, String buyerUsername) {
            return String.format("用户 %s 已支付成功，订单号 %s，请及时处理", buyerUsername, orderId);
        }
    },

    /** 退款申请 */
    REFUND_APPLY {
        @Override
        public String buildContent(Integer orderId, String buyerUsername) {
            return String.format("有买家发起退款，订单编号 %s，请在 7 天内处理", orderId);
        }
    },

    /** 确认收货 */
    CONFIRM_RECEIPT {
        @Override
        public String buildContent(Integer orderId, String buyerUsername) {
            return String.format("订单 %s 用户 %s 已经确认收货", orderId, buyerUsername);
        }
    };

    /** 按固定模板拼消息文本 */
    public abstract String buildContent(Integer orderId, String buyerUsername);
}
