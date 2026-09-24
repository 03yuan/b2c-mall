package com.ysh.order.enums;

/**
 * 支付方式枚举（用于策略选择）
 */
public enum PayTypeEnum {
    ALIPAY,  // 支付宝
    WECHAT;  // 微信

    public static PayTypeEnum fromString(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("支付方式不能为空");
        }
        String cleaned = value.trim().replaceAll("[^a-zA-Z]", "");
        try {
            return PayTypeEnum.valueOf(cleaned.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的支付方式: " + value);
        }
    }
}
