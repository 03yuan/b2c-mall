package com.ysh.order.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ysh.order.bean.CurrentEmployeeBean;
import com.ysh.order.component.RequestHolderComponent;
import com.ysh.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 下单请求体扩展（shopName/skuImage/shopPhone 三个可选快照字段）的 Web 层契约测试（TDD 红阶段）。
 * 对应 .tdd/task/mall-order-manage.md 功能上下文 5.1（Web 层 - CreateOrderRequest 扩展）：
 * - 5.1.1 请求体新增可选字段 shopName、skuImage、shopPhone（不加校验注解；unitPrice 复用 price 字段写入）
 *   Fail 边界：误加 @NotBlank -> 老客户端请求全被 400 拒绝
 *
 * 本测试锁定「Controller 把请求体三个新快照字段透传给 Service.createOrder 新签名」的契约，
 * 以及「三字段可选、不加校验注解」的向后兼容语义。
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：带三字段 -> createOrder 收到三值
 * 2. 类型与格式：不带三字段 -> 透传 null（不加 @NotBlank，老客户端兼容）
 * 3. 重复创建：重复下单三字段均透传
 * 4. 非法输入：shopName 传数字（类型不匹配）-> 400 反序列化失败
 */
class CreateOrderRequestFieldsTest {

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

    private void loginAsBuyer() {
        CurrentEmployeeBean bean = new CurrentEmployeeBean();
        bean.setUsername(BUYER);
        requestHolder.set(bean);
    }

    private String bodyWithSnapshots() {
        return "{\"skuId\":10,\"skuName\":\"测试商品\",\"quantity\":1,\"price\":\"99.00\","
                + "\"receiverName\":\"张三\",\"receiverPhone\":\"13800005678\","
                + "\"receiverAddress\":\"上海市浦东新区\","
                + "\"shopName\":\"测试店铺\",\"skuImage\":\"https://img.example.com/a.png\","
                + "\"shopPhone\":\"13800000001\"}";
    }

    private String bodyWithoutSnapshots() {
        return "{\"skuId\":10,\"skuName\":\"测试商品\",\"quantity\":1,\"price\":\"99.00\","
                + "\"receiverName\":\"张三\",\"receiverPhone\":\"13800005678\","
                + "\"receiverAddress\":\"上海市浦东新区\"}";
    }

    private Object[] snapshotArgs() {
        ArgumentCaptor<String> shopNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> skuImageCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> shopPhoneCaptor = ArgumentCaptor.forClass(String.class);
        verify(orderService).createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                shopNameCaptor.capture(), skuImageCaptor.capture(), shopPhoneCaptor.capture(), any());
        return new Object[]{shopNameCaptor.getValue(), skuImageCaptor.getValue(), shopPhoneCaptor.getValue()};
    }

    // ============ 1. 字段级校验：带三字段透传三值 ============

    @Test
    void 带三字段_透传shopNameSkuImageShopPhone() throws Exception {
        loginAsBuyer();

        mockMvc.perform(post("/order/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithSnapshots()))
                .andExpect(status().isOk());

        Object[] snapshots = snapshotArgs();
        assertEquals("测试店铺", snapshots[0], "shopName 应透传");
        assertEquals("https://img.example.com/a.png", snapshots[1], "skuImage 应透传");
        assertEquals("13800000001", snapshots[2], "shopPhone 应透传");
    }

    // ============ 2. 类型与格式：不带三字段透传 null（不加 @NotBlank） ============

    @Test
    void 不带三字段_透传null且不拒绝() throws Exception {
        loginAsBuyer();

        mockMvc.perform(post("/order/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithoutSnapshots()))
                .andExpect(status().isOk());

        Object[] snapshots = snapshotArgs();
        assertNull(snapshots[0], "不带 shopName 应透传 null（不加 @NotBlank）");
        assertNull(snapshots[1], "不带 skuImage 应透传 null");
        assertNull(snapshots[2], "不带 shopPhone 应透传 null");
    }

    // ============ 3. 重复创建：重复下单三字段均透传 ============

    @Test
    void 重复下单_三字段均透传() throws Exception {
        loginAsBuyer();

        mockMvc.perform(post("/order/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithSnapshots()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/order/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithSnapshots()))
                .andExpect(status().isOk());

        ArgumentCaptor<String> shopNameCaptor = ArgumentCaptor.forClass(String.class);
        verify(orderService, times(2)).createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                shopNameCaptor.capture(), any(), any(), any());
        assertEquals(2, shopNameCaptor.getAllValues().size(), "应产生 2 笔订单");
        assertEquals("测试店铺", shopNameCaptor.getAllValues().get(0), "第 1 单 shopName 不正确");
        assertEquals("测试店铺", shopNameCaptor.getAllValues().get(1), "第 2 单 shopName 不正确");
    }

    // ============ 4. 非法输入：类型不匹配 -> 400 反序列化失败 ============

    static Stream<Arguments> illegalTypeCases() {
        return Stream.of(
                arguments("shopName传数组", "\"shopName\":[1,2]"),
                arguments("shopPhone传对象", "\"shopPhone\":{\"x\":1}")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("illegalTypeCases")
    void 非法类型_反序列化失败400(String caseName, String badField) throws Exception {
        loginAsBuyer();

        String base = "{\"skuId\":10,\"skuName\":\"测试商品\",\"quantity\":1,\"price\":\"99.00\","
                + "\"receiverName\":\"张三\",\"receiverPhone\":\"13800005678\","
                + "\"receiverAddress\":\"上海市浦东新区\"";
        // 把坏字段拼进 body（base 末尾无 }，拼接后补 }
        String body = base + "," + badField + "}";

        mockMvc.perform(post("/order/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(orderService, never()).createOrder(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }
}
