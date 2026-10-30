package com.ysh.order.web.filter;

import com.ysh.order.bean.CurrentEmployeeBean;
import com.ysh.order.component.RequestHolderComponent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 买家接口登录态与身份校验的契约测试（回归护栏）。
 * 对应 .tdd/task/mall-order-manage.md 功能上下文 2.1（Filter 层 - TokenFilter 双身份校验）：
 * - 2.1.1 /order/buyer/** 全部接口要求买家身份；无 token / 无效 token / 商家 token 访问 -> 401
 *   （Fail 边界：Filter 回归为透传 -> 任何人可看别人订单）
 *
 * 三态表驱动（针对买家接口的完整三态集中锁定）：无 token、伪造 token（Redis 无此 key）、商家 token。
 * 覆盖 /order/buyer 下全部已存在接口：page / status-counts / 详情 / delete / cancel。
 *
 * 底层实现已在 shop-order-list.md 2.2 落地（TokenFilter 双身份校验），本测试为回归护栏直接绿，
 * 防止后续 Filter 改动把「买家接口要求买家身份」回归为透传。
 */
class OrderBuyerAuthTest {

    private static final String MERCHANT_TOKEN = "merchant-token-1";
    private static final String BUYER_TOKEN = "buyer-token-1";

    private static final String MERCHANT_JSON = "{\"employeeId\":9,\"shopId\":2,\"username\":\"merchant_a\"}";
    private static final String BUYER_JSON = "{\"userId\":7,\"username\":\"buyer_a\"}";

    private MockMvc mockMvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("b2c:token:" + MERCHANT_TOKEN)).thenReturn(MERCHANT_JSON);
        when(valueOperations.get("b2c:token:" + BUYER_TOKEN)).thenReturn(BUYER_JSON);

        RequestHolderComponent requestHolder = new RequestHolderComponent();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new BuyerProbeController(requestHolder))
                .addFilters(new TokenFilter(redisTemplate, requestHolder))
                .build();
    }

    /**
     * 列：用例名、方法、买家接口路径、Authorization 头（null = 不带）、期望状态码
     */
    static Stream<Arguments> buyerAuthCases() {
        return Stream.of(
                // ---- 无 token ----
                arguments("无token访问买家列表", "GET", "/order/buyer/page", null, 401),
                arguments("无token访问状态计数", "GET", "/order/buyer/status-counts", null, 401),
                arguments("无token访问详情", "GET", "/order/buyer/1", null, 401),
                arguments("无token删除", "POST", "/order/buyer/delete/1", null, 401),
                arguments("无token取消", "POST", "/order/buyer/cancel/1", null, 401),

                // ---- 伪造 token（Redis 无此 key）----
                arguments("伪造token访问买家列表", "GET", "/order/buyer/page", "Bearer ghost-token", 401),
                arguments("伪造token访问状态计数", "GET", "/order/buyer/status-counts", "Bearer ghost-token", 401),

                // ---- 商家 token（身份混用）----
                arguments("商家token访问买家列表", "GET", "/order/buyer/page", "Bearer " + MERCHANT_TOKEN, 401),
                arguments("商家token访问状态计数", "GET", "/order/buyer/status-counts", "Bearer " + MERCHANT_TOKEN, 401),
                arguments("商家token访问详情", "GET", "/order/buyer/1", "Bearer " + MERCHANT_TOKEN, 401),
                arguments("商家token删除", "POST", "/order/buyer/delete/1", "Bearer " + MERCHANT_TOKEN, 401),
                arguments("商家token取消", "POST", "/order/buyer/cancel/1", "Bearer " + MERCHANT_TOKEN, 401)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("buyerAuthCases")
    void 买家接口_非买家身份返回401(String caseName, String method, String path,
                                    String authorization, int expectedStatus) throws Exception {
        MockHttpServletRequestBuilder builder = "POST".equals(method) ? post(path) : get(path);
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }

        MockHttpServletResponse response = mockMvc.perform(builder).andReturn().getResponse();

        assertEquals(expectedStatus, response.getStatus(), caseName + "：状态码不正确");
        assertTrue(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("401"),
                caseName + "：应返回 401 语义，实际：" + response.getContentAsString());
    }

    /** 探针控制器：仅读取 ThreadLocal 返回当前身份，验证 Filter 未透传非买家身份到业务层 */
    @RestController
    static class BuyerProbeController {

        private final RequestHolderComponent holder;

        BuyerProbeController(RequestHolderComponent holder) {
            this.holder = holder;
        }

        @GetMapping("/order/buyer/page")
        CurrentEmployeeBean page() {
            return holder.get();
        }

        @GetMapping("/order/buyer/status-counts")
        CurrentEmployeeBean statusCounts() {
            return holder.get();
        }

        @GetMapping("/order/buyer/{orderId}")
        CurrentEmployeeBean detail(@PathVariable Integer orderId) {
            return holder.get();
        }

        @PostMapping("/order/buyer/delete/{orderId}")
        CurrentEmployeeBean delete(@PathVariable Integer orderId) {
            return holder.get();
        }

        @PostMapping("/order/buyer/cancel/{orderId}")
        CurrentEmployeeBean cancel(@PathVariable Integer orderId) {
            return holder.get();
        }
    }
}
