package com.ysh.order.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ysh.b2cmall.common.response.BaseResponseVO;
import com.ysh.order.bean.CurrentEmployeeBean;
import com.ysh.order.component.RequestHolderComponent;
import com.ysh.order.dao.po.OrderPO;
import com.ysh.order.service.OrderService;
import com.ysh.order.service.impl.OrderServiceImpl;
import com.ysh.order.web.request.CreateOrderRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 下单写入 buyer_username 的表格驱动测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 2.2 任务项 2.2.3：
 * - 带买家 token 下单 -> DB 中 buyer_username = 登录 username
 * - 买家身份缺失 -> 拒绝下单（401/业务错误），不得落库
 *   （Fail 边界：buyer_username 仍为 null -> 买家列表永远查不到自己的单）
 *
 * 分层覆盖：
 * 1. 字段级校验：Controller 把登录上下文的 username 作为 buyer_username 透传给 Service
 * 2. 类型与格式：username 为空串/空白时不得当作有效买家身份
 * 3. 重复创建：同一买家重复下单，每单都写入该买家 username
 * 4. 非法输入：无买家身份（商家身份或未登录）拒绝下单
 */
class OrderCreateBuyerUsernameTest {

    private static final String BUYER = "buyer_a";

    private OrderService orderService;
    private RequestHolderComponent requestHolder;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        when(orderService.createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any()))
                .thenReturn(new HashMap<>(Map.of("orderId", 1, "orderNo", "ORD1", "status", "PENDING")));

        requestHolder = new RequestHolderComponent();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new OrderController(orderService, requestHolder))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    private void loginAsBuyer(String username) {
        CurrentEmployeeBean bean = new CurrentEmployeeBean();
        bean.setUsername(username);
        requestHolder.set(bean);
    }

    private void loginAsMerchant() {
        CurrentEmployeeBean bean = new CurrentEmployeeBean();
        bean.setShopId(2);
        bean.setEmployeeId(9);
        requestHolder.set(bean);
    }

    private String body(Integer quantity, String price) {
        return String.format("{\"skuId\":10,\"skuName\":\"测试商品\",\"quantity\":%s,\"price\":%s,"
                        + "\"receiverName\":\"张三\",\"receiverPhone\":\"13800005678\","
                        + "\"receiverAddress\":\"上海市浦东新区\"}",
                quantity == null ? "null" : quantity, price == null ? "null" : price);
    }

    private String buyerUsernameArg() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(orderService).createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), captor.capture());
        return captor.getValue();
    }

    // ============ 1. 字段级校验：登录 username 透传为 buyer_username ============

    @Test
    void 买家登录下单_透传登录username() throws Exception {
        loginAsBuyer(BUYER);

        mockMvc.perform(post("/order/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1, "99.00")))
                .andExpect(status().isOk());

        assertEquals(BUYER, buyerUsernameArg(), "buyer_username 必须取自登录上下文");
    }

    // ============ 2. 类型与格式：username 空串/空白不算有效买家身份 ============

    /**
     * 列：用例名、登录 username（null 表示未设置）
     */
    static Stream<Arguments> blankUsernameCases() {
        return Stream.of(
                arguments("username为空串", ""),
                arguments("username为空白", "   "),
                arguments("username为null", null)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("blankUsernameCases")
    void 买家身份无效_拒绝下单且不落库(String caseName, String username) throws Exception {
        CurrentEmployeeBean bean = new CurrentEmployeeBean();
        bean.setUsername(username);
        requestHolder.set(bean);

        int status = mockMvc.perform(post("/order/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1, "99.00")))
                .andReturn().getResponse().getStatus();

        assertEquals(401, status, caseName + "：应拒绝下单");
        verify(orderService, never()).createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ============ 3. 重复创建：同一买家连续下单，每单都写入该买家 ============

    @Test
    void 同一买家重复下单_每单均写入username() throws Exception {
        loginAsBuyer(BUYER);

        mockMvc.perform(post("/order/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1, "99.00")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/order/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(2, "50.00")))
                .andExpect(status().isOk());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(orderService, org.mockito.Mockito.times(2))
                .createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                        any(), any(), captor.capture());

        assertEquals(2, captor.getAllValues().size(), "应产生 2 笔订单");
        assertEquals(BUYER, captor.getAllValues().get(0), "第 1 单 buyer_username 不正确");
        assertEquals(BUYER, captor.getAllValues().get(1), "第 2 单 buyer_username 不正确");
    }

    // ============ 4. 非法输入：商家身份不得调用买家下单接口 ============

    @Test
    void 商家身份下单_拒绝且不落库() throws Exception {
        loginAsMerchant();

        int status = mockMvc.perform(post("/order/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1, "99.00")))
                .andReturn().getResponse().getStatus();

        assertEquals(401, status, "商家身份不应被当作买家下单");
        verify(orderService, never()).createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 未登录下单_拒绝且不落库() throws Exception {
        requestHolder.clear();

        int status = mockMvc.perform(post("/order/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1, "99.00")))
                .andReturn().getResponse().getStatus();

        assertEquals(401, status, "未登录不应下单");
    }

    // ============ Service 层：buyerUsername 必须写入 PO ============

    @Test
    void Service层_将buyerUsername写入订单PO() {
        com.ysh.order.dao.OrderMapper orderMapper = mock(com.ysh.order.dao.OrderMapper.class);
        OrderServiceImpl service = new OrderServiceImpl();
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);

        Map<String, Object> result = service.createOrder(1, 1, 10, "测试商品", 1, new BigDecimal("99.00"),
                "张三", "13800005678", "上海市浦东新区", null, null, null, BUYER);

        ArgumentCaptor<OrderPO> captor = ArgumentCaptor.forClass(OrderPO.class);
        verify(orderMapper).insert(captor.capture());
        assertEquals(BUYER, captor.getValue().getBuyerUsername(), "PO 的 buyerUsername 必须写入");
        assertNull(result.get("error"));
    }
}
