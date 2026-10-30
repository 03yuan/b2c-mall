package com.ysh.b2cmall.product.web;

import com.ysh.b2cmall.product.bean.CurrentEmployeeBean;
import com.ysh.b2cmall.product.component.RequestHolderComponent;
import com.ysh.b2cmall.product.dao.mapper.SkuMapper;
import com.ysh.b2cmall.product.dao.po.SkuPO;
import com.ysh.b2cmall.product.dao.po.SkuPageResult;
import com.ysh.b2cmall.product.dao.po.SkuQuery;
import com.ysh.b2cmall.product.service.CategoryService;
import com.ysh.b2cmall.product.service.SkuService;
import com.ysh.b2cmall.product.web.advice.GlobalExceptionHandler;
import com.ysh.b2cmall.product.web.controller.ProductController;
import com.ysh.b2cmall.product.web.controller.ProductListController;
import com.ysh.b2cmall.product.web.interceptor.LoginInterceptor;
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
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 商品管理 shopId 强制隔离测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-product-list.md 功能上下文 2.2（Service/DAO 层 - shopId 强制隔离）：
 * - 2.2.1 所有查询/更新/删除 SQL 恒带 shop_id = 登录 shopId，请求参数中的 shopId 一律忽略
 *   （Fail 边界：信任前端 shopId -> 水平越权）
 * - 2.2.2 跨店 id 防护：编辑/下架/删除传入他店 skuId -> 不生效，他店数据零改动
 *   （Fail 边界：UPDATE/DELETE 漏 shop_id 条件 -> 改到他店数据）
 *
 * 用例分类（表格驱动，适配隔离校验）：
 * 1. 字段级校验：Service 收到的 shopId 恒等于登录上下文 shopId（3），即使请求参数传 99
 * 2. 类型与格式：请求参数 shopId 为非数字/小数/负数/注入串/超长/空串时一律忽略、不 500，
 *    Service 仍以登录 shopId 被调用
 * 3. 重复创建：同一越权请求重复调用 -> 每次都以登录 shopId 被调用（不串态、幂等）
 * 4. 非法输入：传他店 skuId / 越权 shopId -> Service 仍只收登录 shopId；
 *    DAO 层跨店 skuId、shopId 为 null 一律 0 行生效、他店数据零改动
 *
 * 两段测试：
 * A. web -> Service 契约：standalone MockMvc + Mockito mock SkuService + 真实 LoginInterceptor
 *    （沿用 Dashboard 测试模式：verify(only()) 断言 Service 仅以登录 shopId 被调用）
 * B. DAO 层隔离：@TempDir 真实临时 SQLite + SkuMapper XML，断言所有写 SQL 恒带 shop_id 条件
 */
class ProductShopIsolationTest {

    @TempDir
    File tempDir;

    private RequestHolderComponent requestHolder;
    private SkuService skuService;
    private MockMvc mockMvc;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private SkuMapper skuMapper;

    @BeforeEach
    void setUp() throws Exception {
        // ---- A 段：web -> Service 契约 ----
        requestHolder = mock(RequestHolderComponent.class);
        skuService = mock(SkuService.class);
        when(skuService.queryPage(any())).thenReturn(new SkuPageResult(Collections.emptyList(), 0));
        when(requestHolder.get()).thenReturn(loginBean(3));
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ProductListController(skuService, requestHolder, mock(CategoryService.class)),
                        new ProductController(skuService, requestHolder))
                .addInterceptors(new LoginInterceptor(requestHolder))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        // ---- B 段：DAO 真实临时 SQLite ----
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "product-isolation-test.db").getAbsolutePath();
        dataSource = new UnpooledDataSource("org.sqlite.JDBC", jdbcUrl, null, null);

        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        try (InputStream in = Resources.getResourceAsStream("mapper/SkuMapper.xml")) {
            new XMLMapperBuilder(in, configuration, "mapper/SkuMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);

        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS tb_sku");
            st.executeUpdate("CREATE TABLE tb_sku ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "shop_id INTEGER NOT NULL,"
                    + "category_id INTEGER NOT NULL,"
                    + "name VARCHAR(100) NOT NULL,"
                    + "description TEXT,"
                    + "price DECIMAL(10,2) NOT NULL,"
                    + "market_price DECIMAL(10,2),"
                    + "stock INTEGER,"
                    + "stock_threshold INTEGER,"
                    + "sales INTEGER DEFAULT 0,"
                    + "fake_sales INTEGER DEFAULT 0,"
                    + "store_stock INTEGER DEFAULT 0,"
                    + "type TINYINT NOT NULL,"
                    + "product_type TINYINT,"
                    + "images VARCHAR(1000),"
                    + "spec VARCHAR(500),"
                    + "delivery_methods VARCHAR(100),"
                    + "sort INTEGER DEFAULT 0,"
                    + "status TINYINT DEFAULT 0,"
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
        }

        session = sqlSessionFactory.openSession(true);
        skuMapper = session.getMapper(SkuMapper.class);

        // 固定数据：id=10 属店铺 1（登录店），id=20 属店铺 2（他店），初始均上架
        insertSku(10, 1, 1, "本店商品A");
        insertSku(20, 2, 1, "他店商品B");
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private CurrentEmployeeBean loginBean(Integer shopId) {
        CurrentEmployeeBean bean = new CurrentEmployeeBean();
        bean.setEmployeeId(7);
        bean.setShopId(shopId);
        bean.setUsername("tom");
        return bean;
    }

    private MockHttpServletRequestBuilder request(String method, String url) {
        switch (method) {
            case "GET":
                return get(url);
            case "POST":
                return post(url);
            case "PUT":
                return put(url);
            case "DELETE":
                return delete(url);
            default:
                throw new IllegalArgumentException("不支持的请求方法：" + method);
        }
    }

    private void insertSku(int id, int shopId, int status, String name) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_sku (id, shop_id, category_id, name, price, type, status)"
                             + " VALUES (?,?,?,?,?,?,?)")) {
            ps.setInt(1, id);
            ps.setInt(2, shopId);
            ps.setInt(3, 1);
            ps.setString(4, name);
            ps.setBigDecimal(5, new BigDecimal("9.90"));
            ps.setInt(6, 1);
            ps.setInt(7, status);
            ps.executeUpdate();
        }
    }

    private int readStatus(int id) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT status FROM tb_sku WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private boolean rowExists(int id) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM tb_sku WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ============ A 段：web -> Service 契约（2.2.1 请求参数 shopId 一律忽略） ============

    /**
     * 列：用例名、请求方法、URL、请求参数 shopId 值、Service 断言（均须且仅以登录 shopId=3 被调用）
     */
    static Stream<Arguments> webShopIdCases() {
        return Stream.of(
                // ----1. 字段级校验：Service 收到的 shopId 恒为登录上下文值，不是请求参数值----
                arguments("商品列表-越权shopId参数被忽略", "GET", "/product/list", "99",
                        (Consumer<SkuService>) s -> verify(s, only()).queryPage(argThat(q -> q != null && Integer.valueOf(3).equals(q.getShopId())))),
                arguments("商品更新-越权shopId参数被忽略", "PUT", "/product/update", "99",
                        (Consumer<SkuService>) s -> verify(s, only()).updateSku(eq(3), any())),
                arguments("上下架-越权shopId参数被忽略", "POST", "/product/status", "99",
                        (Consumer<SkuService>) s -> verify(s, only()).updateStatus(eq(3), any(), any())),
                arguments("删除商品-越权shopId参数被忽略", "DELETE", "/product/delete/20", "99",
                        (Consumer<SkuService>) s -> verify(s, only()).deleteSku(eq(3), eq(20))),

                // ----2. 类型与格式：shopId 参数任何非法类型都被忽略，不 500，隔离不受影响----
                arguments("shopId非数字被忽略", "GET", "/product/list", "abc",
                        (Consumer<SkuService>) s -> verify(s, only()).queryPage(argThat(q -> q != null && Integer.valueOf(3).equals(q.getShopId())))),
                arguments("shopId小数被忽略", "GET", "/product/list", "1.5",
                        (Consumer<SkuService>) s -> verify(s, only()).queryPage(argThat(q -> q != null && Integer.valueOf(3).equals(q.getShopId())))),
                arguments("shopId负数被忽略", "GET", "/product/list", "-9",
                        (Consumer<SkuService>) s -> verify(s, only()).queryPage(argThat(q -> q != null && Integer.valueOf(3).equals(q.getShopId())))),
                arguments("shopId注入串被忽略", "GET", "/product/list", "1' or '1'='1",
                        (Consumer<SkuService>) s -> verify(s, only()).queryPage(argThat(q -> q != null && Integer.valueOf(3).equals(q.getShopId())))),
                arguments("shopId超长溢出被忽略", "GET", "/product/list", "99999999999999999999",
                        (Consumer<SkuService>) s -> verify(s, only()).queryPage(argThat(q -> q != null && Integer.valueOf(3).equals(q.getShopId())))),
                arguments("shopId空串被忽略", "GET", "/product/list", "",
                        (Consumer<SkuService>) s -> verify(s, only()).queryPage(argThat(q -> q != null && Integer.valueOf(3).equals(q.getShopId())))),

                // ----4. 非法输入：他店 skuId + 越权 shopId 组合仍只以登录 shopId 调 Service----
                arguments("删除传他店skuId仍按登录shopId调用", "DELETE", "/product/delete/20", "2",
                        (Consumer<SkuService>) s -> verify(s, only()).deleteSku(eq(3), eq(20))),
                arguments("更新传越权shopId仍按登录shopId调用", "PUT", "/product/update", "2",
                        (Consumer<SkuService>) s -> verify(s, only()).updateSku(eq(3), any()))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("webShopIdCases")
    void 接口_shopId仅取登录上下文_请求参数一律忽略(String name, String method, String url,
                                              String paramShopId, Consumer<SkuService> verifyCall) throws Exception {
        MvcResult result = mockMvc.perform(request(method, url).param("shopId", paramShopId)).andReturn();

        assertNotEquals(500, result.getResponse().getStatus(),
                name + "：shopId 参数被忽略不应导致 500，实际：" + result.getResponse().getStatus());
        verifyCall.accept(skuService);
    }

    // ----3. 重复创建：同一越权请求重复调用 -> 每次都以登录 shopId，不串态----

    @Test
    void 同一越权请求重复调用_每次都以登录shopId_不串态() throws Exception {
        mockMvc.perform(get("/product/list").param("shopId", "99")).andReturn();
        mockMvc.perform(get("/product/list").param("shopId", "99")).andReturn();

        ArgumentCaptor<SkuQuery> captor = ArgumentCaptor.forClass(SkuQuery.class);
        verify(skuService, times(2)).queryPage(captor.capture());
        for (SkuQuery query : captor.getAllValues()) {
            assertEquals(Integer.valueOf(3), query.getShopId(), "每次都必须以登录 shopId 被调用（不串态）");
        }
        verify(skuService, never()).queryPage(argThat(q -> q != null && Integer.valueOf(99).equals(q.getShopId())));
    }

    // ============ B 段：DAO 层隔离（2.2.1/2.2.2 写 SQL 恒带 shop_id 条件） ============

    /**
     * 列：用例名、以店铺 1（登录店）身份执行的 DAO 操作、期望结果
     */
    static Stream<Arguments> daoIsolationCases() {
        SkuPO ownPo = new SkuPO();
        ownPo.setId(10);
        ownPo.setName("本店商品A-改");
        SkuPO crossPo = new SkuPO();
        crossPo.setId(20);
        crossPo.setName("他店商品B-改");
        return Stream.of(
                // ----1. 字段级校验：shop_id 条件在位，本店数据可查可改----
                arguments("本店按id加shopId可查", (Function<SkuMapper, Object>) m -> m.selectByIdWithinShop(10, 1) != null, true),
                arguments("本店下架生效1行", (Function<SkuMapper, Object>) m -> m.updateStatusWithinShop(10, 0, 1), 1),
                arguments("本店更新生效1行", (Function<SkuMapper, Object>) m -> m.updateSkuWithinShop(ownPo, 1), 1),
                arguments("本店删除生效1行", (Function<SkuMapper, Object>) m -> m.deleteWithinShop(10, 1), 1),

                // ----2. 类型与格式：按 shopId 查询只回本店行----
                arguments("按shopId查询只回本店行", (Function<SkuMapper, Object>) m -> m.selectByShopId(1).size(), 1),

                // ----3. 重复创建：重复删除幂等，第二次 0 行----
                arguments("重复删除同sku第二次0行", (Function<SkuMapper, Object>) m -> m.deleteWithinShop(10, 1) + m.deleteWithinShop(10, 1), 1),

                // ----4. 非法输入：跨店 skuId / null shopId / 不存在 id 一律不生效----
                arguments("跨店下架0行生效", (Function<SkuMapper, Object>) m -> m.updateStatusWithinShop(20, 0, 1), 0),
                arguments("跨店更新0行生效", (Function<SkuMapper, Object>) m -> m.updateSkuWithinShop(crossPo, 1), 0),
                arguments("跨店删除0行生效", (Function<SkuMapper, Object>) m -> m.deleteWithinShop(20, 1), 0),
                arguments("跨店按id查不到", (Function<SkuMapper, Object>) m -> m.selectByIdWithinShop(20, 1), null),
                arguments("shopId为null不放行更新", (Function<SkuMapper, Object>) m -> m.updateStatusWithinShop(10, 0, null), 0),
                arguments("shopId为null不放行删除", (Function<SkuMapper, Object>) m -> m.deleteWithinShop(10, null), 0),
                arguments("不存在id删除0行", (Function<SkuMapper, Object>) m -> m.deleteWithinShop(999, 1), 0)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("daoIsolationCases")
    void SQL恒带shopId条件_隔离语义正确(String name, Function<SkuMapper, Object> action, Object expected) {
        Object actual = action.apply(skuMapper);
        assertEquals(expected, actual, name + "：隔离语义不符");
    }

    // ----2.2.2 Fail 边界核心：跨店操作后他店数据零改动----

    @Test
    void 跨店skuId操作_他店数据零改动() throws Exception {
        int updated = skuMapper.updateStatusWithinShop(20, 0, 1);
        int deleted = skuMapper.deleteWithinShop(20, 1);
        SkuPO crossRead = skuMapper.selectByIdWithinShop(20, 1);

        assertEquals(0, updated, "跨店下架必须 0 行生效");
        assertEquals(0, deleted, "跨店删除必须 0 行生效");
        assertNull(crossRead, "跨店查询必须查不到");
        assertEquals(1, readStatus(20), "他店商品 status 必须保持不变");
        assertTrue(rowExists(20), "他店商品记录必须保留");
    }

    // ----2.2.2 更新不得改写 shop_id（防商品被“搬店”）----

    @Test
    void 本店更新_不得改写shopId() throws Exception {
        SkuPO po = new SkuPO();
        po.setId(10);
        po.setName("本店商品A-改名");

        skuMapper.updateSkuWithinShop(po, 1);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT shop_id FROM tb_sku WHERE id = 10");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertEquals(1, rs.getInt(1), "更新语句不得覆盖 shop_id（商品被搬店）");
        }
    }
}
