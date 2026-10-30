package com.ysh.order.web.filter;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ysh.order.bean.CurrentEmployeeBean;
import com.ysh.order.component.RequestHolderComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Token 校验过滤器（双身份）：
 * 1. 从 Authorization 请求头解析 Bearer token，查 Redis（b2c:token:{token}）
 * 2. 载荷含 shopId/employeeId -> 商家身份；载荷含 userId -> 买家身份（username）
 * 3. 按路径强制身份类型：商家接口要求 shopId，买家接口（/order/buyer/**、/order/create）要求 username
 * 4. 放入 RequestHolderComponent（ThreadLocal）供业务层取用，请求结束清理
 */
@Slf4j
@Component
public class TokenFilter extends OncePerRequestFilter {

    private static final String TOKEN_KEY_PREFIX = "b2c:token:";
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final StringRedisTemplate redisTemplate;
    private final RequestHolderComponent requestHolder;

    @Autowired
    public TokenFilter(StringRedisTemplate redisTemplate, RequestHolderComponent requestHolder) {
        this.redisTemplate = redisTemplate;
        this.requestHolder = requestHolder;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-resources")
                || path.equals("/swagger-ui.html");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String token = resolveToken(request);
            if (token == null) {
                write401(response, "未登录或token缺失");
                return;
            }

            String userInfoJson = redisTemplate.opsForValue().get(TOKEN_KEY_PREFIX + token);
            if (userInfoJson == null) {
                write401(response, "token无效或已过期");
                return;
            }

            CurrentEmployeeBean bean = parseIdentity(userInfoJson);

            if (isBuyerPath(request.getRequestURI())) {
                // 买家接口：必须买家身份（username 非空且无 shopId）
                if (isBlank(bean.getUsername()) || bean.getShopId() != null) {
                    write401(response, "需要买家登录态");
                    return;
                }
            } else {
                // 商家接口：必须商家身份（shopId 非空）
                if (bean.getShopId() == null) {
                    write401(response, "需要商家登录态");
                    return;
                }
            }

            requestHolder.set(bean);
            filterChain.doFilter(request, response);
        } finally {
            requestHolder.clear();
        }
    }

    private CurrentEmployeeBean parseIdentity(String userInfoJson) {
        JSONObject json = JSONUtil.parseObj(userInfoJson);
        CurrentEmployeeBean bean = new CurrentEmployeeBean();
        bean.setEmployeeId(json.getInt("employeeId"));
        bean.setShopId(json.getInt("shopId"));
        bean.setUsername(json.getStr("username"));
        return bean;
    }

    /** 买家端接口：买家订单域 + 下单 */
    private boolean isBuyerPath(String path) {
        return path.startsWith("/order/buyer/") || path.equals("/order/create");
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private void write401(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"status\":401,\"message\":\"" + message + "\"}");
    }
}
