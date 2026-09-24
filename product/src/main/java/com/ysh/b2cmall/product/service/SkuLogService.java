package com.ysh.b2cmall.product.service;

public interface SkuLogService {

    /**
     * 记录商品操作日志
     *
     * @param skuId      商品ID
     * @param operatorId 操作人ID
     * @param action     操作类型
     * @param content    操作内容
     */
    void log(Integer skuId, Integer operatorId, String action, String content);
}
