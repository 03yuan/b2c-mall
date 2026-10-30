package com.ysh.order.web;

import com.ysh.order.bean.CurrentEmployeeBean;
import com.ysh.order.component.RequestHolderComponent;
import com.ysh.order.dao.po.BuyerOrderQueryPO;
import com.ysh.order.service.OrderService;
import com.ysh.order.web.advice.GlobalExceptionHandler;
import com.ysh.order.web.vo.BuyerOrderPageVO;
import com.ysh.order.web.vo.BuyerStatusCountsVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 买家端订单列表 GET /order/buyer/page、GET /order/buyer/status-counts 的 Controller 契约测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 10.2.2：
 * - 买家身份强制：username 取自登录上下文，参数 buyerUsername 忽略；商家 token（shopId 非空）→ 401
 *
 * 用例分类：
 * 1. 字段级校验：合法请求透传登录 username + status/orderNo/page/size 到 Service
 * 2. 类型与格式：status/orderNo 可选参数透传；status-counts 无参数
 * 3. 重复创建：同请求重复提交调用两次且响应一致
 * 4. 非法输入：无买家身份（无登录态）→ 401 且 Service 零调用；商家身份（shopId 非空）→ 401
 *
 * standalone MockMvc + mock OrderService；被测物为 BuyerOrderController。
 */
class BuyerOrderControllerTest {

    private static final String LOGIN_USERNAME = "buyer_a";

    private OrderService orderService;
    private RequestHolderComponent requestHolder;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        requestHolder = mock(RequestHolderComponent.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new BuyerOrderController(orderService, requestHolder))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private void buyerLogin() {
        CurrentEmployeeBean current = new CurrentEmployeeBean();
        current.setUsername(LOGIN_USERNAME);
        // 买家身份：shopId 为 null，仅 username 非空
        when(requestHolder.get()).thenReturn(current);
    }

    private void merchantLogin() {
        CurrentEmployeeBean current = new CurrentEmployeeBean();
        current.setEmployeeId(9);
        current.setShopId(2);
        current.setUsername("merchant_a");
        when(requestHolder.get()).thenReturn(current);
    }

    // ============ 1. 字段级校验：合法请求透传登录 username + 参数 ============

    @Test
    void 合法请求_透传登录username与筛选参数() throws Exception {
        buyerLogin();
        BuyerOrderPageVO pageVO = new BuyerOrderPageVO(Collections.emptyList(), 0);
        when(orderService.pageBuyerOrders(any(BuyerOrderQueryPO.class))).thenReturn(pageVO);

        int status = mockMvc.perform(get("/order/buyer/page")
                        .param("status", "PAID")
                        .param("orderNo", "NO-1")
                        .param("page", "2")
                        .param("size", "5"))
                .andReturn().getResponse().getStatus();

        assertEquals(200, status, "合法买家列表请求应返回 200");

        ArgumentCaptor<BuyerOrderQueryPO> captor = ArgumentCaptor.forClass(BuyerOrderQueryPO.class);
        verify(orderService, times(1)).pageBuyerOrders(captor.capture());
        BuyerOrderQueryPO filter = captor.getValue();
        assertEquals(LOGIN_USERNAME, filter.getBuyerUsername(), "buyerUsername 应取自登录上下文而非请求参数");
        assertEquals("PAID", filter.getStatus(), "status 应透传");
        assertEquals("NO-1", filter.getOrderNo(), "orderNo 应透传");
        assertEquals(2, filter.getPage(), "page 应透传");
        assertEquals(5, filter.getSize(), "size 应透传");
    }

    @Test
    void 请求参数带buyerUsername_被忽略() throws Exception {
        buyerLogin();
        when(orderService.pageBuyerOrders(any(BuyerOrderQueryPO.class)))
                .thenReturn(new BuyerOrderPageVO(Collections.emptyList(), 0));

        mockMvc.perform(get("/order/buyer/page").param("buyerUsername", "buyer_b"))
                .andReturn().getResponse().getStatus();

        ArgumentCaptor<BuyerOrderQueryPO> captor = ArgumentCaptor.forClass(BuyerOrderQueryPO.class);
        verify(orderService, times(1)).pageBuyerOrders(captor.capture());
        assertEquals(LOGIN_USERNAME, captor.getValue().getBuyerUsername(),
                "buyerUsername 必须取自登录上下文，忽略请求参数 buyer_b");
    }

    // ============ 2. 类型与格式：可选参数缺省 ============

    @Test
    void 无筛选参数_透传空status与orderNo() throws Exception {
        buyerLogin();
        when(orderService.pageBuyerOrders(any(BuyerOrderQueryPO.class)))
                .thenReturn(new BuyerOrderPageVO(Collections.emptyList(), 0));

        mockMvc.perform(get("/order/buyer/page")).andReturn().getResponse().getStatus();

        ArgumentCaptor<BuyerOrderQueryPO> captor = ArgumentCaptor.forClass(BuyerOrderQueryPO.class);
        verify(orderService, times(1)).pageBuyerOrders(captor.capture());
        assertNull(captor.getValue().getStatus(), "缺省 status 应为 null");
        assertNull(captor.getValue().getOrderNo(), "缺省 orderNo 应为 null");
        assertEquals(1, captor.getValue().getPage(), "缺省 page 应为 1");
        assertEquals(10, captor.getValue().getSize(), "缺省 size 应为 10");
    }

    @Test
    void 状态计数_透传登录username() throws Exception {
        buyerLogin();
        when(orderService.buyerStatusCounts(LOGIN_USERNAME)).thenReturn(new BuyerStatusCountsVO());

        int status = mockMvc.perform(get("/order/buyer/status-counts"))
                .andReturn().getResponse().getStatus();

        assertEquals(200, status, "状态计数请求应返回 200");
        verify(orderService, times(1)).buyerStatusCounts(LOGIN_USERNAME);
    }

    // ============ 3. 重复创建：同请求重复提交 ============

    @Test
    void 同请求重复提交_两次均调用且响应一致() throws Exception {
        buyerLogin();
        when(orderService.pageBuyerOrders(any(BuyerOrderQueryPO.class)))
                .thenReturn(new BuyerOrderPageVO(Collections.emptyList(), 0));

        int first = mockMvc.perform(get("/order/buyer/page"))
                .andReturn().getResponse().getStatus();
        int second = mockMvc.perform(get("/order/buyer/page"))
                .andReturn().getResponse().getStatus();

        assertEquals(first, second, "同请求重复提交响应状态应一致");
        verify(orderService, times(2)).pageBuyerOrders(any(BuyerOrderQueryPO.class));
    }

    // ============ 4. 非法输入：无买家身份 / 商家身份 ============

    @Test
    void 无登录态_返回401且不调用Service() throws Exception {
        when(requestHolder.get()).thenReturn(null);

        int status = mockMvc.perform(get("/order/buyer/page"))
                .andReturn().getResponse().getStatus();

        assertEquals(401, status, "无买家登录态应为 401");
        verifyNoInteractions(orderService);
    }

    @Test
    void 商家身份_返回401且不调用Service() throws Exception {
        merchantLogin();

        int status = mockMvc.perform(get("/order/buyer/page"))
                .andReturn().getResponse().getStatus();

        assertEquals(401, status, "商家 token（shopId 非空）访问买家接口应为 401");
        verifyNoInteractions(orderService);
    }

    @Test
    void 状态计数_无登录态返回401() throws Exception {
        when(requestHolder.get()).thenReturn(null);

        int status = mockMvc.perform(get("/order/buyer/status-counts"))
                .andReturn().getResponse().getStatus();

        assertEquals(401, status, "无买家登录态的状态计数应为 401");
        verifyNoInteractions(orderService);
    }
}
