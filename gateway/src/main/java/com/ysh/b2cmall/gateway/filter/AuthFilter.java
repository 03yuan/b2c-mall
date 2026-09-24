package com.ysh.b2cmall.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 鉴权过滤器（责任链第 3 环）：
 * 1. WhiteUrlFilter 打过白名单标记的请求直接放行
 * 2. 其余请求从 Authorization 头解析 Bearer token
 * 3. 响应式查 Redis（b2c:token:{token}），查不到返回 401
 *
 * 网关基于 WebFlux，必须用 ReactiveStringRedisTemplate，不能用阻塞式 StringRedisTemplate。
 */
@Component
@Slf4j
@Order(3)
public class AuthFilter implements GlobalFilter {

    private static final String TOKEN_KEY_PREFIX = "b2c:token:";
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 白名单请求直接放行
        if (Boolean.TRUE.equals(exchange.getAttribute(WhiteUrlFilter.SKIP_AUTH_ATTR))) {
            return chain.filter(exchange);
        }

        ServerHttpRequest request = exchange.getRequest();
        String token = resolveToken(request);
        if (token == null) {
            return writeUnauthorized(exchange.getResponse(), "未登录或token缺失");
        }

        // 响应式查 Redis：存在则放行，为空（无效/过期）返回 401
        return redisTemplate.opsForValue().get(TOKEN_KEY_PREFIX + token)
                .flatMap(userInfo -> {
                    log.info("token 校验通过：path={}", request.getPath());
                    return chain.filter(exchange);
                })
                .switchIfEmpty(writeUnauthorized(exchange.getResponse(), "token无效或已过期"));
    }

    private String resolveToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(AUTH_HEADER);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }

    private Mono<Void> writeUnauthorized(ServerHttpResponse response, String message) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"status\":401,\"message\":\"" + message + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
