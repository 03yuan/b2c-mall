package com.ysh.b2cmall.product.component;

import com.ysh.b2cmall.common.exception.BizException;

/**
 * 商品类型枚举：把散落的魔法数字 1/2 收敛为语义常量。
 */
public enum ProductType {

    /** 实物商品 */
    PHYSICAL(1),

    /** 虚拟商品 */
    VIRTUAL(2);

    private final int code;

    ProductType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /** 按请求 type 值解析，非法值明确报 400（不再静默 fallback 到实物） */
    public static ProductType from(Integer type) {
        if (type == null) {
            throw BizException.badRequest("商品类型不能为空");
        }
        for (ProductType t : values()) {
            if (t.code == type) {
                return t;
            }
        }
        throw BizException.badRequest("商品类型非法（仅支持 1-实物 / 2-虚拟）");
    }
}
