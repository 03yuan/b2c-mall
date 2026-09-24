package com.ysh.b2cmall.employee.web;

import com.ysh.b2cmall.common.response.BaseResponseVO;
import com.ysh.b2cmall.employee.service.EmployeeService;
import com.ysh.b2cmall.employee.web.request.AddEmployeeRequestVO;
import com.ysh.b2cmall.employee.web.request.LoginRequestVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.Map;

@RestController
@RequestMapping("/employee")
public class EmployeeController {

    @Autowired
    private EmployeeService employeeService;

    @PostMapping("/save")
    public BaseResponseVO<Integer> save(@RequestBody AddEmployeeRequestVO req) {
        Integer id = employeeService.addEmployee(req);
        return new BaseResponseVO<>(id);
    }

    @PostMapping("/login")
    public BaseResponseVO<Map<String, Object>> login(@RequestBody @Valid LoginRequestVO req,
                                                      HttpServletRequest httpRequest) {
        String ip = extractIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        Map<String, Object> result = employeeService.login(req, ip, userAgent);
        return new BaseResponseVO<>(result);
    }

    private String extractIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 多级代理时 X-Forwarded-For 取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
