package com.ysh.b2cmall.shop.service.impl;

import com.ysh.b2cmall.shop.dao.mapper.MessageMapper;
import com.ysh.b2cmall.shop.dao.po.MessagePO;
import com.ysh.b2cmall.shop.service.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class MessageServiceImpl implements MessageService {

    @Autowired
    private MessageMapper messageMapper;

    @Override
    public void saveWeclomeMessage(Integer shopId, String title, String content) {
        MessagePO msg = new MessagePO();
        msg.setShopId(shopId);
        msg.setSenderId(null);
        msg.setTitle(title);
        msg.setContent(content);
        msg.setMsgType(1);
        msg.setIsRead(false);
        msg.setCreatedAt(new Date());
        msg.setUpdatedAt(new Date());
        messageMapper.save(msg);
    }
}
