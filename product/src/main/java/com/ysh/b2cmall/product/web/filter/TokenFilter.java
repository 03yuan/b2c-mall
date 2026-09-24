package com.ysh.b2cmall.product.web.filter;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ysh.b2cmall.product.bean.CurrentEmployeeBean;
import com.ysh.b2cmall.product.component.RequestHolderComponent;
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
 * Token 校验过滤器：
 * 1. 从 Authorization 请求头解析 Bearer token
 * 2. 查 Redis（b2c:token:{token}）获取登录员工信息
 * 3. 放入 RequestHolderComponent（ThreadLocal）供业务层取用
 * 4. 请求结束清理 ThreadLocal
 */
@Slf4j
@Component
public class TokenFilter extends OncePerRequestFilter {

    private static final String TOKEN_KEY_PREFIX = "b2c:token:";
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RequestHolderComponent requestHolder;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/product/create")
                || path.startsWith("/swagger-ui")
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
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"status\":401,\"message\":\"未登录或token缺失\"}");
                return;
            }

            String userInfoJson = redisTemplate.opsForValue().get(TOKEN_KEY_PREFIX + token);
            if (userInfoJson == null) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"status\":401,\"message\":\"token无效或已过期\"}");
                return;
            }

            // 解析登录员工信息并放入 ThreadLocal
            JSONObject json = JSONUtil.parseObj(userInfoJson);
            CurrentEmployeeBean bean = new CurrentEmployeeBean();
            bean.setEmployeeId(json.getInt("employeeId"));
            bean.setShopId(json.getInt("shopId"));
            bean.setUsername(json.getStr("username"));
            requestHolder.set(bean);

            filterChain.doFilter(request, response);
        } finally {
            requestHolder.clear();
        }
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }
}
