package com.ysh.b2cmall.shop.web;

import com.google.common.eventbus.EventBus;
import com.ysh.b2cmall.common.response.BaseResponseVO;
import com.ysh.b2cmall.shop.service.ShopService;
import com.ysh.b2cmall.shop.service.event.ShopRegisterEvent;
import com.ysh.b2cmall.shop.web.request.ShopLoginRequestVO;
import com.ysh.b2cmall.shop.web.request.ShopRegisterRequestVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/shop")
public class ShopController {

    @Autowired
    public ShopService shopService;
    @Autowired
    public EventBus eventBus;

    @PostMapping("/register")
    public BaseResponseVO<?> register(@RequestBody @Validated ShopRegisterRequestVO req) {
        Integer id = shopService.register(req);

        ShopRegisterEvent event = new ShopRegisterEvent();
        event.setShopId(id);
        event.setShopName(req.getShopName());
        event.setAdminAccount(req.getAdminAccount());
        event.setAdminPwd(req.getAdminPassword());
        eventBus.post(event);
        return new BaseResponseVO<>();
    }

    @PostMapping("/login")
    public BaseResponseVO<Map<String, Object>> login(@RequestBody @Validated ShopLoginRequestVO req) {
        Map<String, Object> result = shopService.login(req);
        return new BaseResponseVO<>(result);
    }
}
