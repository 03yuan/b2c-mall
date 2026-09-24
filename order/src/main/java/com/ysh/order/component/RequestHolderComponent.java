package com.ysh.order.component;

import com.ysh.order.bean.CurrentEmployeeBean;
import org.springframework.stereotype.Component;

/**
 * 请求上下文持有组件：用 ThreadLocal 存储当前登录员工信息
 */
@Component
public class RequestHolderComponent {

    private static final ThreadLocal<CurrentEmployeeBean> HOLDER = new ThreadLocal<>();

    public void set(CurrentEmployeeBean bean) {
        HOLDER.set(bean);
    }

    public CurrentEmployeeBean get() {
        return HOLDER.get();
    }

    public void clear() {
        HOLDER.remove();
    }
}
