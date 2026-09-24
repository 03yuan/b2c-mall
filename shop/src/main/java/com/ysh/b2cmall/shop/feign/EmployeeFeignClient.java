package com.ysh.b2cmall.shop.feign;

import com.ysh.b2cmall.common.response.BaseResponseVO;
import com.ysh.b2cmall.shop.feign.request.AddEmployeeRequestVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "employee-service", path = "/employee")
public interface EmployeeFeignClient {

    @PostMapping("/save")
    BaseResponseVO<Integer> save(@RequestBody AddEmployeeRequestVO req);
}
