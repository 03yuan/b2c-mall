package com.ysh.b2cmall.shop.web;

import com.ysh.b2cmall.shop.dao.mapper.ShopMessageMapper;
import com.ysh.b2cmall.shop.service.impl.ShopMessageServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.File;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /shop/dashboard/messages 表格驱动测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-dashboard.md 功能上下文 5.3：
 * - 5.3.1 返回消息列表按时间倒序（最新在最上），每条含 content、orderId、created_at、type
 *   （Fail 边界：正序排列或漏 orderId 字段）
 * - 5.3.2 只含当前登录店铺的消息（shopId 隔离），支持滚动分页（page/size）
 *   （Fail 边界：漏 shopId 过滤泄露他店消息）
 * 登录态契约与 DashboardController 一致：Authorization: Bearer + X-Shop-Id，
 * 请求参数 shopId 被忽略（测试恒带越权参数 shopId=9）。
 *
 * 用例分类（表格驱动，端到端：Controller + 真实 Service/DAO + 真实 SQLite）：
 * 1. 字段级校验：每条消息四字段（content/orderId/created_at/type）齐全、缺登录态 -> 401
 * 2. 类型与格式：倒序、分页 page/size 生效、size 大于总数返回全部、created_at 为时间字符串
 * 3. 重复创建：查询接口无创建语义 -> 重复请求幂等、无副作用（见独立测试）
 * 4. 非法输入：跨店消息不泄露、越权 shopId 参数被忽略、page/size 非法 -> 400、
 *    无消息 -> data 为 null（前端「暂无数据」）
 *
 * 每行末尾带基线对照请求（shop1 无分页恒为倒序 3,2,1）：
 * 既保证红阶段必失败，又证明本行请求未破坏基础查询。
 *
 * 测试库为 @TempDir 下的临时 SQLite 库（绝对路径连接串）。
 */
class DashboardMessageQueryTest {

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "shop-test.db").getAbsolutePath();
        dataSource = new UnpooledDataSource("org.sqlite.JDBC", jdbcUrl, null, null);

        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        try (InputStream is = Resources.getResourceAsStream("mapper/ShopMessageMapper.xml")) {
            new XMLMapperBuilder(is, configuration, "mapper/ShopMessageMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        session = factory.openSession(true);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE tb_shop_message (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "shop_id INTEGER NOT NULL," +
                    "type VARCHAR(20) NOT NULL," +
                    "content VARCHAR(500) NOT NULL," +
                    "order_id INTEGER NOT NULL," +
                    "created_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
            // shop 1：3 条（order_id 1 最旧、3 最新）-> 倒序 3,2,1
            insertMessage(stmt, 1, 1, "2026-10-22 10:00:00");
            insertMessage(stmt, 1, 2, "2026-10-22 11:00:00");
            insertMessage(stmt, 1, 3, "2026-10-22 12:00:00");
            // shop 2：2 条（4、5）-> 倒序 5,4（跨店隔离验证）
            insertMessage(stmt, 2, 4, "2026-10-22 10:00:00");
            insertMessage(stmt, 2, 5, "2026-10-22 12:00:00");
            // shop 3：无消息 -> 暂无数据
        }

        ShopMessageMapper mapper = session.getMapper(ShopMessageMapper.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DashboardMessageController(new ShopMessageServiceImpl(mapper)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (session != null) {
            session.close();
        }
    }

    private void insertMessage(Statement stmt, int shopId, int orderId, String createdAt) throws Exception {
        stmt.execute("INSERT INTO tb_shop_message (shop_id, type, content, order_id, created_at) VALUES ("
                + shopId + ", 'PAY_SUCCESS', '测试消息 " + orderId + "', " + orderId + ", '" + createdAt + "')");
    }

    /** 每条消息断言四字段齐全（5.3.1：漏 orderId 字段即 Fail）+ 顺序与期望订单号一致 */
    private void assertMessageList(ResultActions actions, String expectedIds) throws Exception {
        String[] ids = expectedIds.split(",");
        actions.andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(ids.length));
        for (int i = 0; i < ids.length; i++) {
            String path = "$.data[" + i + "]";
            actions.andExpect(jsonPath(path + ".content").isString())
                    .andExpect(jsonPath(path + ".orderId").value(Integer.parseInt(ids[i])))
                    .andExpect(jsonPath(path + ".created_at").isString())
                    .andExpect(jsonPath(path + ".type").isString());
        }
    }

    /**
     * 列：用例名、Authorization 头（"ok"=Bearer tok、"-"=缺）、X-Shop-Id 头（"-"=缺）、
     *     page（"-"=不传）、size（"-"=不传）、期望 HTTP 状态、
     *     期望订单号序列（倒序，"-"=期望 data 为 null；400/401 行不查数据）
     */
    static Stream<Arguments> messageCases() {
        return Stream.of(
                // ----1. 字段级校验：每条四字段齐全（runner 断言），缺登录态 -> 401----
                arguments("消息条目四字段齐全", "ok", "1", "-", "-", 200, "3,2,1"),
                arguments("缺Authorization返回401", "-", "1", "-", "-", 401, "-"),
                arguments("缺X-Shop-Id返回401", "ok", "-", "-", "-", 401, "-"),

                // ----2. 类型与格式：倒序、分页生效、created_at 为时间字符串（runner 断言）----
                arguments("按时间倒序最新在最上", "ok", "1", "-", "-", 200, "3,2,1"),
                arguments("分页第1页size2", "ok", "1", "1", "2", 200, "3,2"),
                arguments("分页第2页size2", "ok", "1", "2", "2", 200, "1"),
                arguments("size大于总数返回全部", "ok", "1", "1", "100", 200, "3,2,1"),

                // ----3. 重复创建（查询接口无创建语义 -> 重复请求幂等）见下方独立测试----

                // ----4. 非法输入：跨店不泄露、越权参数被忽略、非法分页 -> 400、无消息 -> null----
                arguments("跨店消息不泄露", "ok", "2", "-", "-", 200, "5,4"),
                arguments("越权shopId参数被忽略", "ok", "1", "-", "-", 200, "3,2,1"),
                arguments("page为0返回400", "ok", "1", "0", "2", 400, "-"),
                arguments("size为负返回400", "ok", "1", "1", "-1", 400, "-"),
                arguments("page非数字返回400", "ok", "1", "abc", "2", 400, "-"),
                arguments("无消息店铺暂无数据null", "ok", "3", "-", "-", 200, "-")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("messageCases")
    void 消息查询_倒序分页与shopId隔离(String caseName, String authSpec, String shopIdHeader,
                                      String page, String size, int expectedStatus,
                                      String expectedIds) throws Exception {
        MockHttpServletRequestBuilder req = get("/shop/dashboard/messages");
        if (!"-".equals(authSpec)) {
            req.header("Authorization", "Bearer tok");
        }
        if (!"-".equals(shopIdHeader)) {
            req.header("X-Shop-Id", shopIdHeader);
        }
        if (!"-".equals(page)) {
            req.param("page", page);
        }
        if (!"-".equals(size)) {
            req.param("size", size);
        }
        // 越权参数恒在：请求参数 shopId 必须被忽略，只查登录店铺
        req.param("shopId", "9");

        ResultActions actions = mockMvc.perform(req);
        actions.andExpect(status().is(expectedStatus));

        if (expectedStatus == 200) {
            if ("-".equals(expectedIds)) {
                actions.andExpect(jsonPath("$.data").value(nullValue()));
            } else {
                assertMessageList(actions, expectedIds);
            }
        }

        // 基线对照：shop1 无分页恒为倒序 3,2,1（保证红阶段必失败，也证明本行未破坏基础查询）
        ResultActions base = mockMvc.perform(get("/shop/dashboard/messages")
                .header("Authorization", "Bearer tok")
                .header("X-Shop-Id", "1")
                .param("shopId", "9"));
        base.andExpect(status().isOk());
        assertMessageList(base, "3,2,1");
    }

    // ----3. 重复创建：查询接口无创建语义 -> 重复请求幂等、无副作用----

    @Test
    void 重复请求_倒序结果一致且幂等() throws Exception {
        for (int i = 0; i < 2; i++) {
            ResultActions actions = mockMvc.perform(get("/shop/dashboard/messages")
                    .header("Authorization", "Bearer tok")
                    .header("X-Shop-Id", "1")
                    .param("shopId", "9"));
            actions.andExpect(status().isOk());
            assertMessageList(actions, "3,2,1");
        }
    }
}
