package com.ysh.b2cmall.shop.service;

import com.ysh.b2cmall.shop.web.vo.MessageVO;

import java.util.List;

/**
 * 店铺消息查询服务（tb_shop_message）。
 * 约定：只查登录店铺的消息，shopId 由登录上下文注入。
 */
public interface ShopMessageService {

    /**
     * 分页查询消息（按时间倒序，最新在最上）
     * @param shopId 登录店铺 id
     * @param page   页码（从 1 开始）
     * @param size   每页条数
     */
    List<MessageVO> getMessages(Integer shopId, Integer page, Integer size);
}
