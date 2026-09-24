package com.ysh.b2cmall.shop.service;

import com.ysh.b2cmall.shop.web.request.ShopLoginRequestVO;
import com.ysh.b2cmall.shop.web.request.ShopRegisterRequestVO;

import java.util.Map;

public interface ShopService {

    Integer register(ShopRegisterRequestVO vo);

    Map<String, Object> login(ShopLoginRequestVO vo);
}
