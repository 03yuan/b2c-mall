package com.ysh.b2cmall.product.web.interceptor;

import com.ysh.b2cmall.product.bean.CurrentEmployeeBean;
import com.ysh.b2cmall.product.component.RequestHolderComponent;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 登录态拦截器：
 * 保护店铺商品管理的新增接口，无登录态或登录态缺 shopId 时返回 401。
 * 对应 .tdd/task/shop-product-list.md 功能上下文 2.1（Filter/组件层 - 登录态注入）：
 * 业务代码只从登录上下文（RequestHolderComponent，由 TokenFilter 注入）取 shopId，
 * shopId 为 null 时直接拒绝，防止查询条件失效查出全店数据。
 */
public class LoginInterceptor implements HandlerInterceptor {

    private final RequestHolderComponent requestHolder;

    public LoginInterceptor(RequestHolderComponent requestHolder) {
        this.requestHolder = requestHolder;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        CurrentEmployeeBean bean = requestHolder.get();
        if (bean == null || bean.getShopId() == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"status\":401,\"message\":\"未登录或登录态无效\"}");
            return false;
        }
        return true;
    }
}
