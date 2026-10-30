package com.ysh.b2cmall.shop.web;

import com.ysh.b2cmall.common.response.BaseResponseVO;
import com.ysh.b2cmall.shop.service.DashboardService;
import com.ysh.b2cmall.shop.web.vo.DashboardSummaryVO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 店铺 Dashboard 接口。
 * 登录态约定：
 * - Authorization：登录 token（网关校验）
 * - X-Shop-Id：网关解析 token 后透传的登录店铺 id，shopId 只从登录态取，
 *   不接受请求参数指定 shopId（防水平越权）
 */
@RestController
@RequestMapping("/shop/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/summary")
    public ResponseEntity<BaseResponseVO<?>> summary(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Shop-Id", required = false) String shopId) {
        Integer loginShopId = resolveLoginShopId(authorization, shopId);
        if (loginShopId == null) {
            BaseResponseVO<Object> unauthorized = new BaseResponseVO<>(401, "未登录或登录态无效");
            return ResponseEntity.status(401).body(unauthorized);
        }
        DashboardSummaryVO data = dashboardService.getSummary(loginShopId);
        return ResponseEntity.ok(new BaseResponseVO<>(data));
    }

    /**
     * 从登录态解析店铺 id：Authorization 必须为 Bearer token，X-Shop-Id 必须为正整数，
     * 任一不满足视为未登录（返回 null）。
     */
    private Integer resolveLoginShopId(String authorization, String shopId) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        if (shopId == null || !shopId.matches("\\d+")) {
            return null;
        }
        try {
            int id = Integer.parseInt(shopId);
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
