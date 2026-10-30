package com.ysh.order.web.filter;

import com.ysh.order.bean.CurrentEmployeeBean;
import com.ysh.order.component.RequestHolderComponent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * order-service TokenFilter 双身份解析表格驱动测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 2.2：
 * - 2.2.1 Bearer 解析 + Redis b2c:token: 校验 + swagger 白名单；商家载荷(employeeId/shopId) -> 商家身份，
 *         买家载荷(userId) -> 买家身份(username)
 * - 2.2.2 商家接口要求 shopId 非空（买家 token 访问 -> 401）；
 *         买家接口（/order/buyer/**、/order/create）要求买家身份（商家 token 访问 -> 401）
 *
 * 测试用测试专用探针控制器读取 RequestHolderComponent，验证过滤器是否把身份写入 ThreadLocal；
 * 生产控制器尚未就绪也不影响本用例的红/绿。
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：Authorization 头缺失/前缀错误/空 token
 * 2. 类型与格式：token 不存在 Redis；商家/买家载荷字段解析
 * 3. 重复创建：同一 token 连续请求身份一致
 * 4. 非法输入：身份混用、swagger 白名单放行
 */
class OrderTokenFilterTest {

    private static final String MERCHANT_TOKEN = "merchant-token-1";
    private static final String BUYER_TOKEN = "buyer-token-1";

    private static final String MERCHANT_JSON = "{\"employeeId\":9,\"shopId\":2,\"username\":\"merchant_a\"}";
    private static final String BUYER_JSON = "{\"userId\":7,\"username\":\"buyer_a\"}";

    private RequestHolderComponent requestHolder;
    private MockMvc mockMvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("b2c:token:" + MERCHANT_TOKEN)).thenReturn(MERCHANT_JSON);
        when(valueOperations.get("b2c:token:" + BUYER_TOKEN)).thenReturn(BUYER_JSON);

        requestHolder = new RequestHolderComponent();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new IdentityProbeController(requestHolder))
                .addFilters(new TokenFilter(redisTemplate, requestHolder))
                .build();
    }

    // ============ 1/2. 字段级校验 + 类型与格式：认证头与 token 校验（表格驱动） ============

    /**
     * 列：用例名、Authorization 头（null 表示不带该头）、期望状态码、期望消息片段
     */
    static Stream<Arguments> authCases() {
        return Stream.of(
                // ----1. 字段级校验----
                arguments("缺少Authorization头", null, 401, "未登录或token缺失"),
                arguments("Authorization为空串", "", 401, "未登录或token缺失"),
                arguments("无Bearer前缀", "Basic abc123", 401, "未登录或token缺失"),
                arguments("Bearer后为空token", "Bearer ", 401, "未登录或token缺失"),

                // ----2. 类型与格式----
                arguments("token在Redis中不存在", "Bearer ghost-token", 401, "token无效或已过期"),
                arguments("token大小写畸形仍视为无效", "Bearer MERCHANT-TOKEN-1", 401, "token无效或已过期")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("authCases")
    void 商家接口_认证失败返回401(String caseName, String authorization, int expectedStatus,
                                 String expectedMessage) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(withAuth(get("/order/page"), authorization))
                .andReturn().getResponse();

        assertEquals(expectedStatus, response.getStatus(), caseName + "：状态码不正确");
        assertTrue(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                        .contains(expectedMessage),
                caseName + "：响应应包含「" + expectedMessage + "」，实际：" + response.getContentAsString());
    }

    // ============ 2. 商家身份解析（employeeId/shopId/username 写入 ThreadLocal） ============

    @Test
    void 商家token_访问商家接口_写入商家身份() throws Exception {
        mockMvc.perform(get("/order/page").header("Authorization", "Bearer " + MERCHANT_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shopId").value(2))
                .andExpect(jsonPath("$.employeeId").value(9))
                .andExpect(jsonPath("$.username").value("merchant_a"))
                .andExpect(jsonPath("$.buyerIdentity").value(false));
    }

    // ============ 2. 买家身份解析（userId 载荷 -> username） ============

    @Test
    void 买家token_访问买家接口_写入买家身份() throws Exception {
        mockMvc.perform(get("/order/buyer/page").header("Authorization", "Bearer " + BUYER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("buyer_a"))
                .andExpect(jsonPath("$.shopId").doesNotExist())
                .andExpect(jsonPath("$.buyerIdentity").value(true));
    }

    // ============ 3. 重复创建：同一 token 连续请求身份一致 ============

    @Test
    void 同一token连续请求_身份一致且不残留() throws Exception {
        mockMvc.perform(get("/order/page").header("Authorization", "Bearer " + MERCHANT_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shopId").value(2));

        mockMvc.perform(get("/order/page").header("Authorization", "Bearer " + MERCHANT_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shopId").value(2));

        // 请求结束后 ThreadLocal 必须清理，避免串号
        assertNull(requestHolder.get(), "请求结束后必须清理 ThreadLocal");
    }

    // ============ 4. 非法输入：身份混用（2.2.2） ============

    /**
     * 列：用例名、请求方法、路径、Authorization 头、期望状态码
     */
    static Stream<Arguments> identityMismatchCases() {
        return Stream.of(
                // ----4. 身份混用：买家 token 访问商家接口 / 商家 token 访问买家接口----
                arguments("买家token访问商家订单列表", "GET", "/order/page", "Bearer " + BUYER_TOKEN, 401),
                arguments("买家token访问商家发货接口", "POST", "/order/ship/1", "Bearer " + BUYER_TOKEN, 401),
                arguments("商家token访问买家订单列表", "GET", "/order/buyer/page", "Bearer " + MERCHANT_TOKEN, 401),
                arguments("商家token访问买家下单接口", "POST", "/order/create", "Bearer " + MERCHANT_TOKEN, 401),
                arguments("无token访问买家订单列表", "GET", "/order/buyer/page", null, 401)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("identityMismatchCases")
    void 身份不匹配_返回401(String caseName, String method, String path, String authorization,
                            int expectedStatus) throws Exception {
        MockHttpServletResponse response;
        if ("POST".equals(method)) {
            response = mockMvc.perform(withAuth(post(path), authorization)).andReturn().getResponse();
        } else {
            response = mockMvc.perform(withAuth(get(path), authorization)).andReturn().getResponse();
        }

        assertEquals(expectedStatus, response.getStatus(), caseName + "：状态码不正确");
        assertTrue(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("401"),
                caseName + "：应返回 401 语义，实际：" + response.getContentAsString());
    }

    // ============ 4. 非法输入：swagger 白名单放行 ============

    @Test
    void swagger文档路径_无token放行() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }

    /** 仅在非 null 时附加 Authorization 头（null 表示"不带该头"） */
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withAuth(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, String authorization) {
        return authorization == null ? builder : builder.header("Authorization", authorization);
    }

    // ============ 测试专用探针控制器（不参与生产构建） ============

    @RestController
    static class IdentityProbeController {

        private final RequestHolderComponent holder;

        IdentityProbeController(RequestHolderComponent holder) {
            this.holder = holder;
        }

        @GetMapping("/order/page")
        Map<String, Object> merchantPage() {
            return probe();
        }

        @GetMapping("/order/buyer/page")
        Map<String, Object> buyerPage() {
            return probe();
        }

        @PostMapping("/order/create")
        Map<String, Object> create() {
            return probe();
        }

        @PostMapping("/order/ship/{orderId}")
        Map<String, Object> ship() {
            return probe();
        }

        @GetMapping("/v3/api-docs")
        Map<String, Object> apiDocs() {
            return probe();
        }

        private Map<String, Object> probe() {
            CurrentEmployeeBean bean = holder.get();
            Map<String, Object> body = new HashMap<>();
            if (bean != null) {
                if (bean.getShopId() != null) {
                    body.put("shopId", bean.getShopId());
                }
                if (bean.getEmployeeId() != null) {
                    body.put("employeeId", bean.getEmployeeId());
                }
                if (bean.getUsername() != null) {
                    body.put("username", bean.getUsername());
                }
                body.put("buyerIdentity", bean.getShopId() == null && bean.getUsername() != null);
            }
            return body;
        }
    }
}
