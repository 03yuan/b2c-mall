package com.ysh.b2cmall.employee;

import com.google.common.eventbus.EventBus;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.ysh.b2cmall.employee.dao.mapper")
public class EmployeeApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmployeeApplication.class, args);
    }

    /**
     * 在主类内提供 EventBus Bean，避免新建 config 类。
     * 给 LoginEventHandler 注册 @Subscribe 用。
     */
    @Bean
    public EventBus eventBus() {
        return new EventBus();
    }
}
