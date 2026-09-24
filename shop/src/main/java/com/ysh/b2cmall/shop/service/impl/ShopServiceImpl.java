package com.ysh.b2cmall.shop.service.impl;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ysh.b2cmall.shop.dao.mapper.ShopMapper;
import com.ysh.b2cmall.shop.dao.po.ShopPO;
import com.ysh.b2cmall.shop.service.ShopService;
import com.ysh.b2cmall.shop.web.request.ShopLoginRequestVO;
import com.ysh.b2cmall.shop.web.request.ShopRegisterRequestVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class ShopServiceImpl implements ShopService {

    private static final String TOKEN_KEY_PREFIX = "b2c:token:";
    private static final long TOKEN_TTL_HOURS = 24L;

    @Autowired
    private ShopMapper shopMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public Integer register(ShopRegisterRequestVO vo) {
        int count = shopMapper.exist(vo.getShopName());
        Assert.equals(count, 0, "店铺名已存在");

        ShopPO shopPO = new ShopPO();
        BeanUtils.copyProperties(vo, shopPO);
        // BCrypt 加密管理员密码，原 vo 不变（ShopRegisterEvent 仍带明文给 Feign 调用 employee-service.save，
        // 由 employee-service 自己加密入库；这样 tb_shop.admin_password 与 tb_employee.password 都是 BCrypt）
        shopPO.setAdminPassword(BCrypt.hashpw(vo.getAdminPassword()));
        shopPO.setStatus(1);

        Assert.notBlank(shopPO.getShopName());
        Assert.notBlank(shopPO.getAdminAccount());
        Assert.notBlank(shopPO.getAdminPassword());

        int row = shopMapper.registerShop(shopPO);
        Assert.equals(row, 1, "保存到数据库异常");
        return shopPO.getId();
    }

    @Override
    public Map<String, Object> login(ShopLoginRequestVO vo) {
        ShopPO shop = shopMapper.findByShopNameAndAccount(vo.getShopName(), vo.getAdminAccount());
        Assert.notNull(shop, "店铺名、账号或密码错误");

        boolean pwdOk = BCrypt.checkpw(vo.getAdminPassword(), shop.getAdminPassword());
        Assert.isTrue(pwdOk, "店铺名、账号或密码错误");

        Assert.equals(shop.getStatus(), 1, "店铺已被禁用，请联系管理员");

        // 生成 token 并存入 Redis（与 employee 模块共用同一套 b2c:token: 前缀）
        String token = IdUtil.fastSimpleUUID();
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("shopId", shop.getId());
        userInfo.put("shopName", shop.getShopName());
        userInfo.put("adminAccount", shop.getAdminAccount());
        userInfo.put("logoUrl", shop.getLogoUrl());

        try {
            redisTemplate.opsForValue().set(
                    TOKEN_KEY_PREFIX + token,
                    objectMapper.writeValueAsString(userInfo),
                    TOKEN_TTL_HOURS,
                    TimeUnit.HOURS
            );
        } catch (Exception e) {
            throw new RuntimeException("登录态写入失败", e);
        }

        Map<String, Object> result = new HashMap<>(userInfo);
        result.put("token", token);
        return result;
    }
}
