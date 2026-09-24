package com.ysh.b2cmall.shop.service.event.handler;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.ysh.b2cmall.common.response.BaseResponseVO;
import com.ysh.b2cmall.shop.feign.EmployeeFeignClient;
import com.ysh.b2cmall.shop.feign.request.AddEmployeeRequestVO;
import com.ysh.b2cmall.shop.service.event.ShopRegisterEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@Slf4j
public class InitEmployeeEventHandle implements InitializingBean {

    @Autowired
    private EventBus eventBus;
    @Autowired
    private EmployeeFeignClient employeeFeignClient;

    @Subscribe
    public void handler(ShopRegisterEvent event) {
        log.info("收到注册成功事件:{}，调用employee service 初始化账户", event);

        AddEmployeeRequestVO req = new AddEmployeeRequestVO();
        req.setShopId(event.getShopId());
        req.setUsername(event.getAdminAccount());
        req.setPassword(event.getAdminPwd());
        BaseResponseVO<Integer> resp = employeeFeignClient.save(req);
        if (!Objects.equals(resp.getStatus(), 200)) {
            log.error("保存用户失败");
            return;
        }
        log.info("保存员工成功id：{}", resp.getData());
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        eventBus.register(this);
    }
}
