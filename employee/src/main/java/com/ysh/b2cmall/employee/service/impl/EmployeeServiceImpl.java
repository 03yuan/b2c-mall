package com.ysh.b2cmall.employee.service.impl;

import cn.hutool.core.lang.Assert;
import cn.hutool.crypto.digest.BCrypt;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.google.common.eventbus.EventBus;
import com.ysh.b2cmall.employee.dao.mapper.EmployeeMapper;
import com.ysh.b2cmall.employee.dao.po.EmployeePO;
import com.ysh.b2cmall.employee.service.EmployeeService;
import com.ysh.b2cmall.employee.service.event.LoginEvent;
import com.ysh.b2cmall.employee.web.request.AddEmployeeRequestVO;
import com.ysh.b2cmall.employee.web.request.LoginRequestVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class EmployeeServiceImpl implements EmployeeService {

    private static final String TOKEN_KEY_PREFIX = "b2c:token:";
    private static final long TOKEN_TTL_HOURS = 24L;

    @Autowired
    private EmployeeMapper employeeMapper;

    @Autowired
    private EventBus eventBus;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public Integer addEmployee(AddEmployeeRequestVO vo) {
        Assert.notBlank(vo.getUsername());
        Assert.notNull(vo.getShopId());
        Assert.notBlank(vo.getPassword());

        EmployeePO po = new EmployeePO();
        BeanUtils.copyProperties(vo, po);
        po.setPassword(BCrypt.hashpw(vo.getPassword())); // BCrypt 加密
        po.setAvatarUrl("/avatars/default.png");
        po.setStatus(1);

        Integer row = employeeMapper.save(po);
        Assert.equals(row, 1, "保存员工信息失败");
        return po.getId();
    }

    @Override
    public Map<String, Object> login(LoginRequestVO req, String ip, String userAgent) {
        EmployeePO employee = employeeMapper.selectByUsernameAndShopId(req.getUsername(), req.getShopId());

        // 构造事件公共字段
        LoginEvent event = new LoginEvent();
        event.setShopId(req.getShopId());
        event.setUsername(req.getUsername());
        event.setIp(ip);
        event.setDevice(parseDevice(userAgent));
        event.setLocation(fetchLocationByIp(ip));
        event.setUserAgent(userAgent);
        event.setLoginTime(new Date());

        // 用户不存在
        if (employee == null) {
            event.setStatus(0);
            eventBus.post(event);
            throw new RuntimeException("账号或密码错误");
        }
        event.setEmployeeId(employee.getId());

        // 密码校验
        if (!BCrypt.checkpw(req.getPassword(), employee.getPassword())) {
            event.setStatus(0);
            eventBus.post(event);
            throw new RuntimeException("账号或密码错误");
        }

        // 账号禁用
        if (employee.getStatus() == null || employee.getStatus() != 1) {
            event.setStatus(0);
            eventBus.post(event);
            throw new RuntimeException("账号已禁用");
        }

        // 成功
        event.setStatus(1);
        eventBus.post(event);

        // 生成 token，写 Redis
        String token = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("employeeId", employee.getId());
        userInfo.put("shopId", employee.getShopId());
        userInfo.put("username", employee.getUsername());
        userInfo.put("avatarUrl", employee.getAvatarUrl());

        redisTemplate.opsForValue().set(
                TOKEN_KEY_PREFIX + token,
                JSONUtil.toJsonStr(userInfo),
                TOKEN_TTL_HOURS,
                TimeUnit.HOURS
        );

        Map<String, Object> result = new HashMap<>(userInfo);
        result.put("token", token);
        return result;
    }

    private String parseDevice(String userAgent) {
        if (userAgent == null || userAgent.isEmpty()) {
            return "unknown";
        }
        return userAgent.toLowerCase().contains("mobile") ? "mobile" : "desktop";
    }

    private String fetchLocationByIp(String ip) {
        try {
            String resp = HttpUtil.get("http://ip-api.com/json/" + ip + "?lang=zh-CN", 3000);
            JSONObject json = JSONUtil.parseObj(resp);
            Integer status = json.getInt("status");
            if (status != null && status == 1) {
                return json.getStr("country") + " " + json.getStr("city");
            }
        } catch (Exception e) {
            log.warn("获取 IP 地理位置失败：{}", e.getMessage());
        }
        return null;
    }
}
