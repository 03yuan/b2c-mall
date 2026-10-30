package com.ysh.b2cmall.user.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ysh.b2cmall.user.service.impl.UserServiceImpl;
import com.ysh.b2cmall.user.web.advice.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /user/register 表格驱动测试（TDD 红阶段）。
 * 预期行为（来自 .tdd/task/user-register.md）：
 * - 字段级校验失败 -> 400 + 中文提示
 * - 类型/格式错误 -> 400
 * - 重复注册同名用户 -> 400 且提示"用户名已存在"
 * - 非法输入（畸形 JSON/空请求体）-> 400
 */
class UserControllerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new UserController(new UserServiceImpl(mockRedisTemplate(), new ObjectMapper())))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();

    @SuppressWarnings("unchecked")
    private static StringRedisTemplate mockRedisTemplate() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));
        return redisTemplate;
    }

    // ============ 表格驱动用例：字段级校验 / 类型与格式 / 非法输入 ============

    static Stream<Arguments> registerCases() {
        return Stream.of(
                // ----1. 字段级校验----
                arguments("缺username", "{\"password\":\"123456\"}", 400, "用户名"),
                arguments("缺password", "{\"username\":\"alice\"}", 400, "密码"),
                arguments("username为空串", "{\"username\":\"\",\"password\":\"123456\"}", 400, "用户名"),
                arguments("password为空串", "{\"username\":\"alice\",\"password\":\"\"}", 400, "密码"),

                // ----2. 类型与格式----
                arguments("username不足3位", "{\"username\":\"ab\",\"password\":\"123456\"}", 400, "用户名"),
                arguments("username超过20位", "{\"username\":\"" + "a".repeat(21) + "\",\"password\":\"123456\"}", 400, "用户名"),
                arguments("username含中文", "{\"username\":\"张三\",\"password\":\"123456\"}", 400, "用户名"),
                arguments("username含特殊字符", "{\"username\":\"a b c\",\"password\":\"123456\"}", 400, "用户名"),
                arguments("password不足6位", "{\"username\":\"alice\",\"password\":\"12345\"}", 400, "密码"),
                arguments("password超过20位", "{\"username\":\"alice\",\"password\":\"" + "1".repeat(21) + "\"}", 400, "密码"),
                arguments("nickname超过50字符", "{\"username\":\"alice\",\"password\":\"123456\",\"nickname\":\"" + "n".repeat(51) + "\"}", 400, "昵称"),
                arguments("username类型错误", "{\"username\":123,\"password\":\"123456\"}", 400, null),
                arguments("password类型错误", "{\"username\":\"alice\",\"password\":123456}", 400, null),

                // ----3. 非法输入----
                arguments("JSON结构非法", "{invalid", 400, null),
                arguments("请求体为空", "", 400, null)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("registerCases")
    void create(String caseName, String body, int expectedStatus, String expectedMessage) throws Exception {
        ResultActions actions = mockMvc.perform(post("/user/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));

        actions.andExpect(status().is(expectedStatus));
        if (expectedMessage != null) {
            actions.andExpect(jsonPath("$.message").value(containsString(expectedMessage)));
        }
    }

    // ============ 4. 重复创建 ============

    @Test
    void create_重复注册_应拒绝() throws Exception {
        String body = "{\"username\":\"alice\",\"password\":\"123456\"}";

        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").exists());

        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("用户名已存在")));
    }

    // ============ 登录：字段级校验 / 类型与格式 / 非法输入 / 失败分支（表格驱动） ============

    static Stream<Arguments> loginCases() {
        return Stream.of(
                // ----1. 字段级校验----
                arguments("登录-缺username", "{\"password\":\"123456\"}", 400, "用户名"),
                arguments("登录-缺password", "{\"username\":\"alice\"}", 400, "密码"),
                arguments("登录-username空串", "{\"username\":\"\",\"password\":\"123456\"}", 400, "用户名"),
                arguments("登录-password空串", "{\"username\":\"alice\",\"password\":\"\"}", 400, "密码"),

                // ----2. 类型与格式----
                arguments("登录-username类型错误", "{\"username\":123,\"password\":\"123456\"}", 400, null),
                arguments("登录-password类型错误", "{\"username\":\"alice\",\"password\":123456}", 400, null),

                // ----3. 非法输入----
                arguments("登录-JSON结构非法", "{invalid", 400, null),
                arguments("登录-请求体为空", "", 400, null),

                // ----4. 用户名不存在 / 账号禁用----
                arguments("登录-用户名不存在", "{\"username\":\"nobody\",\"password\":\"123456\"}", 400, "用户名或密码错误"),
                arguments("登录-账号禁用", "{\"username\":\"disabled_user\",\"password\":\"123456\"}", 400, "账号已禁用")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("loginCases")
    void login(String caseName, String body, int expectedStatus, String expectedMessage) throws Exception {
        ResultActions actions = mockMvc.perform(post("/user/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));

        actions.andExpect(status().is(expectedStatus));
        if (expectedMessage != null) {
            actions.andExpect(jsonPath("$.message").value(containsString(expectedMessage)));
        }
    }

    // ============ 登录：成功 / 重复登录 / 防枚举文案 ============

    @Test
    void login_注册后登录_返回token和用户信息() throws Exception {
        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice2\",\"password\":\"123456\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice2\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value(matchesPattern("^[0-9a-f]{32}$")))
                .andExpect(jsonPath("$.data.userId").exists())
                .andExpect(jsonPath("$.data.username").value("alice2"))
                .andExpect(jsonPath("$.data.nickname").exists())
                .andExpect(jsonPath("$.data.avatarUrl").exists());
    }

    @Test
    void login_重复登录_均成功() throws Exception {
        String body = "{\"username\":\"alice4\",\"password\":\"123456\"}";
        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value(matchesPattern("^[0-9a-f]{32}$")));

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value(matchesPattern("^[0-9a-f]{32}$")));
    }

    @Test
    void login_密码错误_文案与用户不存在一致() throws Exception {
        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice3\",\"password\":\"123456\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice3\",\"password\":\"wrong1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void login_禁用且密码错误_返回统一文案() throws Exception {
        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"disabled_user\",\"password\":\"wrong1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }
}