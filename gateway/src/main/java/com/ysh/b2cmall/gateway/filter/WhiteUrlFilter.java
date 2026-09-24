package com.ysh.b2cmall.gateway.filter;

import com.ysh.b2cmall.gateway.config.AuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 白名单过滤器（责任链第 1 环）：
 * 命中白名单（注册/登录/swagger 等）的请求打标记，后续 AuthFilter 看到标记跳过鉴权。
 * 注意：不直接短路，始终 chain.filter 放行，保证 LogginFilter 仍会记录日志。
 */
@Component
@Slf4j
@Order(1)
@RequiredArgsConstructor
public class WhiteUrlFilter implements GlobalFilter {

    /** exchange 属性 key：true 表示该请求命中白名单，无需鉴权 */
    public static final String SKIP_AUTH_ATTR = "b2c:skipAuth";

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /** 白名单配置，由 @ConfigurationProperties 绑定 YAML 列表 */
    private final AuthProperties authProperties;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        boolean matched = authProperties.getWhiteUrls().stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));
        if (matched) {
            log.info("命中白名单，跳过鉴权：{}", path);
            exchange.getAttributes().put(SKIP_AUTH_ATTR, Boolean.TRUE);
        }
        return chain.filter(exchange);
    }
}
