package com.ysh.b2cmall.user.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ysh.b2cmall.user.exception.BusinessException;
import com.ysh.b2cmall.user.service.impl.UserServiceImpl;
import com.ysh.b2cmall.user.web.request.LoginRequestVO;
import com.ysh.b2cmall.user.web.request.RegisterRequestVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * user-service 登录写 Redis 买家 token 的表格驱动测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 2.1（user-service - 买家 token 落 Redis）：
 * - 2.1.1 登录成功后写 Redis b2c:token:{token}，载荷含 userId、username，TTL 24 小时
 *   （Fail 边界：不写 Redis → 买家接口全线 401）
 * - 2.1.2 登录失败（密码错误、用户不存在、账号禁用）不写 token
 *   （Fail 边界：失败也发 token → 禁用账号可登买家端）
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：Redis 载荷 userId/username 齐全且取值正确
 * 2. 类型与格式：token 32 位十六进制、key 前缀 b2c:token:、TTL 24 小时、载荷为合法 JSON
 * 3. 重复创建：重复登录每次生成新 token 且各自独立写入
 * 4. 非法输入：各类登录失败零写入（对照组：成功必写入）
 *
 * 约定与 employee/shop 登录一致：前缀 b2c:token:、TTL 24 小时、JSON 载荷。
 */
class UserLoginTokenTest {

    private static final String TOKEN_KEY_PREFIX = "b2c:token:";
    private static final long TOKEN_TTL_HOURS = 24L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private UserServiceImpl userService;

    /** 一次 token 写入的完整快照 */
    private record TokenWrite(String key, String value, long ttl, TimeUnit unit, String token) {
    }

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        valueOperations = ops;
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        userService = new UserServiceImpl(redisTemplate, MAPPER);
    }

    private Long register(String username) {
        RegisterRequestVO req = new RegisterRequestVO();
        req.setUsername(username);
        req.setPassword("123456");
        return userService.register(req);
    }

    private LoginRequestVO loginReq(String username, String password) {
        LoginRequestVO req = new LoginRequestVO();
        req.setUsername(username);
        req.setPassword(password);
        return req;
    }

    /** 捕获唯一一次 Redis 写入，并带上本次返回的 token 组成快照 */
    private TokenWrite captureSingleWrite(String token) {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> ttl = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<TimeUnit> unit = ArgumentCaptor.forClass(TimeUnit.class);

        verify(valueOperations).set(key.capture(), value.capture(), ttl.capture(), unit.capture());
        return new TokenWrite(key.getValue(), value.getValue(), ttl.getValue(), unit.getValue(), token);
    }

    // ============ 1. 字段级校验：Redis 载荷字段齐全且取值正确 ============

    /**
     * 列：用例名、载荷字段名、期望取值来源（userId 需按注册返回的 ID 断言）
     */
    static Stream<Arguments> payloadFieldCases() {
        return Stream.of(
                arguments("载荷含userId", "userId", true),
                arguments("载荷含username", "username", false)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("payloadFieldCases")
    void login_成功_Redis载荷字段齐全且取值正确(String caseName, String field, boolean expectUserId)
            throws Exception {
        Long userId = register("alice");
        Map<String, Object> data = userService.login(loginReq("alice", "123456"));

        TokenWrite write = captureSingleWrite((String) data.get("token"));
        JsonNode payload = MAPPER.readTree(write.value());

        assertTrue(payload.has(field), caseName + "：载荷缺少字段 " + field);
        String expected = expectUserId ? String.valueOf(userId) : "alice";
        assertEquals(expected, payload.get(field).asText(), caseName + "：字段取值不正确");
    }

    // ============ 2. 类型与格式：token/key/TTL/载荷格式 ============

    /**
     * 列：用例名、对该次写入的格式断言
     */
    static Stream<Arguments> storageFormatCases() {
        return Stream.of(
                arguments("token为32位十六进制", (Consumer<TokenWrite>) write ->
                        assertTrue(write.token().matches("^[0-9a-f]{32}$"),
                                "token 应为 32 位十六进制，实际：" + write.token())),
                arguments("Redis key以b2c:token:为前缀", (Consumer<TokenWrite>) write ->
                        assertTrue(write.key().startsWith(TOKEN_KEY_PREFIX),
                                "key 应以 " + TOKEN_KEY_PREFIX + " 开头，实际：" + write.key())),
                arguments("Redis key为前缀拼接token", (Consumer<TokenWrite>) write ->
                        assertEquals(TOKEN_KEY_PREFIX + write.token(), write.key(),
                                "key 必须严格等于前缀 + token")),
                arguments("TTL为24小时", (Consumer<TokenWrite>) write -> {
                    assertEquals(TOKEN_TTL_HOURS, write.ttl(), "TTL 应为 24");
                    assertEquals(TimeUnit.HOURS, write.unit(), "TTL 单位应为小时");
                }),
                arguments("载荷为合法JSON对象", (Consumer<TokenWrite>) write -> {
                    try {
                        JsonNode payload = MAPPER.readTree(write.value());
                        assertTrue(payload.isObject(), "载荷应为 JSON 对象");
                    } catch (Exception e) {
                        fail("载荷不是合法 JSON：" + write.value());
                    }
                }),
                arguments("返回值含token且与Redis key一致", (Consumer<TokenWrite>) write ->
                        assertNotNull(write.token(), "登录返回值应包含 token"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storageFormatCases")
    void login_成功_Redis写入符合约定格式(String caseName, Consumer<TokenWrite> assertion) {
        register("bob");
        Map<String, Object> data = userService.login(loginReq("bob", "123456"));

        TokenWrite write = captureSingleWrite((String) data.get("token"));

        assertion.accept(write);
    }

    // ============ 3. 重复创建：重复登录每次新 token 独立写入 ============

    @Test
    void login_重复登录_每次生成新token且各自写入() {
        register("carol");

        Map<String, Object> first = userService.login(loginReq("carol", "123456"));
        Map<String, Object> second = userService.login(loginReq("carol", "123456"));

        String firstToken = (String) first.get("token");
        String secondToken = (String) second.get("token");
        assertNotEquals(firstToken, secondToken, "重复登录应生成不同 token");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, times(2)).set(key.capture(), value.capture(), anyLong(), any(TimeUnit.class));

        assertEquals(TOKEN_KEY_PREFIX + firstToken, key.getAllValues().get(0), "首次登录 key 不正确");
        assertEquals(TOKEN_KEY_PREFIX + secondToken, key.getAllValues().get(1), "再次登录 key 不正确");
        assertNotEquals(key.getAllValues().get(0), key.getAllValues().get(1), "两次登录的 key 不得相同");
    }

    // ============ 4. 非法输入：登录失败零写入（对照组：成功必写入） ============

    /**
     * 列：用例名、用户名、密码、期望异常文案
     */
    static Stream<Arguments> invalidLoginCases() {
        return Stream.of(
                arguments("用户不存在", "nobody", "123456", "用户名或密码错误"),
                arguments("密码错误", "dave", "wrong1", "用户名或密码错误"),
                arguments("账号禁用", "disabled_user", "123456", "账号已禁用")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidLoginCases")
    void login_失败不写token_成功才写token(String caseName, String username, String password,
                                          String expectedMessage) {
        if (!"nobody".equals(username) && !"disabled_user".equals(username)) {
            register(username);
        }

        BusinessException ex = null;
        try {
            userService.login(loginReq(username, password));
        } catch (BusinessException e) {
            ex = e;
        }
        assertNotNull(ex, caseName + "：应抛出 BusinessException");
        assertEquals(expectedMessage, ex.getMessage(), caseName + "：异常文案不正确");

        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        // 对照组：成功登录必须写入一次（红阶段由此失败，同时防止「永不全写」蒙混过关）
        register("contrast_user");
        userService.login(loginReq("contrast_user", "123456"));
        verify(valueOperations, times(1)).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }
}
