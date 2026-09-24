package com.ysh.b2cmall.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关鉴权配置，对应 application.yml 中 b2c.auth 前缀。
 * 用 @ConfigurationProperties 接收 YAML 列表（@Value 不支持 YAML 列表绑定）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "b2c.auth")
public class AuthProperties {

    /**
     * 白名单路径（无需登录即可访问），支持 Ant 风格：* 匹配单层、** 匹配多层
     */
    private List<String> whiteUrls = new ArrayList<>();
}
