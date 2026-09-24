package com.ysh.b2cmall.product.service;

import com.ysh.b2cmall.product.dao.po.SkuPO;

public interface SkuService {

    /**
     * 保存商品信息（返回自增ID）
     */
    Integer saveSku(SkuPO po);

    /**
     * 上架
     */
    void putOnSale(Integer skuId);
}
