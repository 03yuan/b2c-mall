package com.ysh.b2cmall.product.service.impl;

import com.ysh.b2cmall.product.dao.mapper.SkuLogMapper;
import com.ysh.b2cmall.product.dao.po.SkuLogPO;
import com.ysh.b2cmall.product.service.SkuLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SkuLogServiceImpl implements SkuLogService {

    @Autowired
    private SkuLogMapper skuLogMapper;

    @Override
    public void log(Integer skuId, Integer operatorId, String action, String content) {
        SkuLogPO po = new SkuLogPO();
        po.setSkuId(skuId);
        po.setOperatorId(operatorId);
        po.setAction(action);
        po.setContent(content);
        skuLogMapper.insert(po);
    }
}
