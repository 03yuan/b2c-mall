package com.ysh.b2cmall.shop.service.impl;

import com.ysh.b2cmall.shop.dao.mapper.ShopMessageMapper;
import com.ysh.b2cmall.shop.dao.po.ShopMessagePO;
import com.ysh.b2cmall.shop.service.ShopMessageService;
import com.ysh.b2cmall.shop.web.vo.MessageVO;

import java.util.ArrayList;
import java.util.List;

/**
 * 店铺消息查询服务实现。
 */
public class ShopMessageServiceImpl implements ShopMessageService {

    private final ShopMessageMapper shopMessageMapper;

    public ShopMessageServiceImpl(ShopMessageMapper shopMessageMapper) {
        this.shopMessageMapper = shopMessageMapper;
    }

    @Override
    public List<MessageVO> getMessages(Integer shopId, Integer page, Integer size) {
        int offset = (page - 1) * size;
        List<ShopMessagePO> poList = shopMessageMapper.selectPageByShopId(shopId, offset, size);
        List<MessageVO> result = new ArrayList<>();
        for (ShopMessagePO po : poList) {
            MessageVO vo = new MessageVO();
            vo.setContent(po.getContent());
            vo.setOrderId(po.getOrderId());
            vo.setCreatedAt(po.getCreatedAt());
            vo.setType(po.getType());
            result.add(vo);
        }
        return result;
    }
}
