package com.ysh.b2cmall.shop.web;

import com.ysh.b2cmall.common.response.BaseResponseVO;
import com.ysh.b2cmall.shop.service.ShopMessageService;
import com.ysh.b2cmall.shop.web.vo.MessageVO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Dashboard 消息通知接口。
 * 登录态约定与 DashboardController 一致：
 * - Authorization：登录 token（网关校验）
 * - X-Shop-Id：网关解析 token 后透传的登录店铺 id，shopId 只从登录态取，
 *   不接受请求参数指定 shopId（防水平越权）
 */
@RestController
@RequestMapping("/shop/dashboard")
public class DashboardMessageController {

    private final ShopMessageService shopMessageService;

    public DashboardMessageController(ShopMessageService shopMessageService) {
        this.shopMessageService = shopMessageService;
    }

    /**
     * 消息通知列表（滚动分页，按时间倒序，仅当前登录店铺）
     */
    @GetMapping("/messages")
    public ResponseEntity<BaseResponseVO<?>> messages(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "shopId", required = false) String shopId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Shop-Id", required = false) String shopIdHeader) {
        Integer loginShopId = resolveLoginShopId(authorization, shopIdHeader);
        if (loginShopId == null) {
            BaseResponseVO<Object> unauthorized = new BaseResponseVO<>(401, "未登录或登录态无效");
            return ResponseEntity.status(401).body(unauthorized);
        }
        int pageNum = page == null ? 1 : page;
        int sizeNum = size == null ? 10 : size;
        if (pageNum < 1 || sizeNum < 1) {
            BaseResponseVO<Object> badRequest = new BaseResponseVO<>(400, "分页参数不合法");
            return ResponseEntity.status(400).body(badRequest);
        }
        // 请求参数 shopId 被忽略，只查登录店铺（防水平越权）
        List<MessageVO> messages = shopMessageService.getMessages(loginShopId, pageNum, sizeNum);
        BaseResponseVO<List<MessageVO>> body =
                new BaseResponseVO<>(messages == null || messages.isEmpty() ? null : messages);
        return ResponseEntity.ok(body);
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
