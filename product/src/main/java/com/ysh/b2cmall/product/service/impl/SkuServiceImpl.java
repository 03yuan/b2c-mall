package com.ysh.b2cmall.product.service.impl;

import cn.hutool.core.lang.Assert;
import com.ysh.b2cmall.product.dao.mapper.SkuMapper;
import com.ysh.b2cmall.product.dao.po.SkuPO;
import com.ysh.b2cmall.product.service.SkuService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SkuServiceImpl implements SkuService {

    @Autowired
    private SkuMapper skuMapper;

    @Override
    public Integer saveSku(SkuPO po) {
        int row = skuMapper.insert(po);
        Assert.equals(row, 1, "保存商品失败");
        return po.getId();
    }

    @Override
    public void putOnSale(Integer skuId) {
        int row = skuMapper.updateStatus(skuId, 1);
        Assert.equals(row, 1, "上架失败");
    }
}
