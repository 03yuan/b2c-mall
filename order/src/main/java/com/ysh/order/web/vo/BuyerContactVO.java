package com.ysh.order.web.vo;

import lombok.Data;

/**
 * 联系买家 VO（shop-order-list.md 功能上下文 9.2）：
 * 返回买家账号 + 脱敏手机号，供前端唤起聊天窗口（唤起为前端职责）。
 */
@Data
public class BuyerContactVO {

    /** 买家账号（历史单未写入时为 null -> 组装为空串） */
    private String buyerUsername;

    /** 脱敏手机号（隐私保护，不得明文） */
    private String maskedPhone;
}
