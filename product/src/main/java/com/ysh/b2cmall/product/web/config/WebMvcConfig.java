package com.ysh.b2cmall.product.web.config;

import com.ysh.b2cmall.product.component.RequestHolderComponent;
import com.ysh.b2cmall.product.web.interceptor.LoginInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册登录态拦截器：
 * /product/** 全部要求登录，/product/create 沿用既有免登白名单（与 TokenFilter 一致）。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private RequestHolderComponent requestHolder;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor(requestHolder))
                .addPathPatterns("/product/**")
                .excludePathPatterns("/product/create");
    }
}
