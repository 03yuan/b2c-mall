package com.ysh.order.dao;

import com.ysh.order.dao.po.OrderPO;
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

import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 登录态强制数据隔离的真实 SQLite 测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 2.3（Service/DAO 层 - 强制隔离）：
 * - 2.3.1 商家侧查询/更新 SQL 恒带 shop_id = 登录 shopId，请求参数中的 shopId 一律忽略
 *   （Fail 边界：信任前端 shopId -> 水平越权）
 * - 2.3.2 买家侧查询/更新 SQL 恒带 buyer_username = 登录 username AND buyer_deleted = 0
 *   （Fail 边界：漏 buyer_deleted -> 已删除订单复活；漏 buyer_username -> 看到他人订单）
 * - 2.3.3 跨身份 orderId 不生效（查询返回 null、更新/删除 0 行），目标数据原样
 *   （Fail 边界：UPDATE/DELETE 漏身份条件 -> 改到他人数据）
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：本店/本人可命中，且返回字段完整
 * 2. 类型与格式：shopId 为 null 不查全表；username 大小写/空格敏感；已删订单不可见
 * 3. 重复创建：重复查询结果一致；重复逻辑删除幂等（第二次 0 行）
 * 4. 非法输入：跨店查询/更新、跨买家查询/删除均不生效且目标数据不变
 *
 * 测试库为 @TempDir 下的临时 SQLite 库；陷阱数据：店铺 1 三单 + 店铺 2 一单 + 买家 A 一条已删订单。
 */
class OrderIsolationTest {

    private static final int SHOP_A = 1;
    private static final int SHOP_B = 2;
    private static final String BUYER_A = "buyer_a";
    private static final String BUYER_B = "buyer_b";

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private OrderMapper orderMapper;
    private int orderNoSeq = 0;

    // 陷阱数据 id
    private int shopA_pending;      // 店1 / buyer_a / 未删 / PENDING
    private int shopA_paid;         // 店1 / buyer_a / 未删 / PAID
    private int shopA_buyerB;       // 店1 / buyer_b / 未删 / SHIPPED（买家 A 的他单）
    private int shopB_pending;      // 店2 / buyer_b / 未删 / PENDING（跨店陷阱）
    private int shopA_deleted;      // 店1 / buyer_a / 已删 / PENDING（买家已删订单）

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "order-isolation.db").getAbsolutePath();
        dataSource = new UnpooledDataSource("org.sqlite.JDBC", jdbcUrl, null, null);

        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        // 与 application.yml 的 mybatis.configuration.map-underscore-to-camel-case: true 保持一致
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream in = Resources.getResourceAsStream("mapper/OrderMapper.xml")) {
            new XMLMapperBuilder(in, configuration, "mapper/OrderMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);

        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS tb_order");
            st.executeUpdate("CREATE TABLE tb_order ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "order_no VARCHAR(64) NOT NULL UNIQUE,"
                    + "shop_id INTEGER NOT NULL,"
                    + "sku_id INTEGER NOT NULL,"
                    + "sku_name VARCHAR(200) NOT NULL,"
                    + "quantity INTEGER NOT NULL,"
                    + "total_price DECIMAL(10,2) NOT NULL,"
                    + "status VARCHAR(20) NOT NULL DEFAULT 'PENDING',"
                    + "pay_type VARCHAR(20),"
                    + "pay_time DATETIME,"
                    + "receiver_name VARCHAR(50) NOT NULL,"
                    + "receiver_phone VARCHAR(20) NOT NULL,"
                    + "receiver_address VARCHAR(500) NOT NULL,"
                    + "buyer_username VARCHAR(64),"
                    + "buyer_deleted INTEGER DEFAULT 0,"
                    + "buyer_fav INTEGER DEFAULT 0,"
                    + "shop_phone VARCHAR(20),"
                    + "shop_name VARCHAR(100),"
                    + "sku_image VARCHAR(500),"
                    + "unit_price DECIMAL(10,2),"
                    + "logistics_no VARCHAR(64),"
                    + "logistics_type VARCHAR(20),"
                    + "create_time DATETIME DEFAULT (datetime('now','localtime')),"
                    + "update_time DATETIME DEFAULT (datetime('now','localtime')))");
        }

        session = sqlSessionFactory.openSession(true);
        orderMapper = session.getMapper(OrderMapper.class);

        shopA_pending = insertOrder(SHOP_A, "PENDING", BUYER_A, 0);
        shopA_paid = insertOrder(SHOP_A, "PAID", BUYER_A, 0);
        shopA_buyerB = insertOrder(SHOP_A, "SHIPPED", BUYER_B, 0);
        shopB_pending = insertOrder(SHOP_B, "PENDING", BUYER_B, 0);
        shopA_deleted = insertOrder(SHOP_A, "PENDING", BUYER_A, 1);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private int insertOrder(int shopId, String status, String buyerUsername, int buyerDeleted) throws Exception {
        orderNoSeq++;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                             + " status, receiver_name, receiver_phone, receiver_address,"
                             + " buyer_username, buyer_deleted)"
                             + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "NO-" + orderNoSeq);
            ps.setInt(2, shopId);
            ps.setInt(3, 1);
            ps.setString(4, "测试商品");
            ps.setInt(5, 2);
            ps.setBigDecimal(6, new BigDecimal("19.80"));
            ps.setString(7, status);
            ps.setString(8, "张三");
            ps.setString(9, "13800005678");
            ps.setString(10, "上海市浦东新区");
            ps.setString(11, buyerUsername);
            ps.setInt(12, buyerDeleted);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private OrderPO reload(int id) {
        return orderMapper.selectById(id);
    }

    // ============ 1. 字段级校验：本店/本人命中且字段完整 ============

    @Test
    void 商家本店查询_返回完整字段() {
        OrderPO order = orderMapper.selectByIdAndShopId(shopA_pending, SHOP_A);

        assertNotNull(order, "本店订单应可查");
        assertEquals(SHOP_A, order.getShopId(), "shopId 不正确");
        assertEquals("NO-" + shopA_pending, order.getOrderNo(), "orderNo 不正确");
        assertEquals(BUYER_A, order.getBuyerUsername(), "buyerUsername 应随查询返回");
        assertEquals("PENDING", order.getStatus(), "status 不正确");
    }

    @Test
    void 买家本人查询_返回完整字段() {
        OrderPO order = orderMapper.selectByIdAndBuyerUsername(shopA_paid, BUYER_A);

        assertNotNull(order, "本人订单应可查");
        assertEquals(BUYER_A, order.getBuyerUsername(), "buyerUsername 不正确");
        assertEquals("PAID", order.getStatus(), "status 不正确");
    }

    // ============ 2. 类型与格式：边界参数与已删订单 ============

    @Test
    void shopId为null_不返回任何订单() {
        assertNull(orderMapper.selectByIdAndShopId(shopA_pending, null),
                "shopId 为 null 时不得退化为全表查询");
    }

    /**
     * 列：用例名、查询用 username、期望是否命中
     */
    static Stream<Arguments> buyerUsernameFormatCases() {
        return Stream.of(
                arguments("本人确切username命中", "buyer_a", true),
                arguments("大小写不符不命中", "BUYER_A", false),
                arguments("前后空格不命中", " buyer_a ", false),
                arguments("空串不命中", "", false),
                arguments("null不命中", null, false)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("buyerUsernameFormatCases")
    void 买家查询_username格式边界(String caseName, String username, boolean shouldHit) {
        OrderPO order = orderMapper.selectByIdAndBuyerUsername(shopA_paid, username);

        if (shouldHit) {
            assertNotNull(order, caseName + "：应命中本人订单");
        } else {
            assertNull(order, caseName + "：不得命中");
        }
    }

    @Test
    void 买家已删订单_查询不可见() {
        assertNull(orderMapper.selectByIdAndBuyerUsername(shopA_deleted, BUYER_A),
                "buyer_deleted=1 的订单不得出现在买家侧");
    }

    // ============ 3. 重复创建：重复查询一致、重复删除幂等 ============

    @Test
    void 重复查询_结果一致() {
        OrderPO first = orderMapper.selectByIdAndShopId(shopA_pending, SHOP_A);
        OrderPO second = orderMapper.selectByIdAndShopId(shopA_pending, SHOP_A);

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.getOrderNo(), second.getOrderNo(), "重复查询结果应一致");
        assertEquals(first.getStatus(), second.getStatus(), "重复查询状态应一致");
    }

    @Test
    void 重复逻辑删除_第二次零行且不复活() {
        int first = orderMapper.softDeleteByBuyer(shopA_paid, BUYER_A);
        int second = orderMapper.softDeleteByBuyer(shopA_paid, BUYER_A);

        assertEquals(1, first, "首次删除应影响 1 行");
        assertEquals(0, second, "重复删除应幂等（0 行）");
        assertEquals(1, reload(shopA_paid).getBuyerDeleted(), "订单应保持已删除标记");
    }

    // ============ 4. 非法输入：跨身份零生效且目标数据原样 ============

    /**
     * 列：用例名、查询 orderId、登录 shopId、期望是否命中
     */
    static Stream<Arguments> crossShopQueryCases() {
        return Stream.of(
                arguments("他店订单不可查", "OTHER_SHOP", SHOP_A, false),
                arguments("不存在的订单返回null", "NOT_EXISTS", SHOP_A, false),
                arguments("本店订单可查（对照组）", "OWN", SHOP_A, true)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("crossShopQueryCases")
    void 商家查询_跨店不可见(String caseName, String target, int loginShopId, boolean shouldHit) {
        int orderId = switch (target) {
            case "OTHER_SHOP" -> shopB_pending;
            case "NOT_EXISTS" -> 99999;
            default -> shopA_pending;
        };

        OrderPO order = orderMapper.selectByIdAndShopId(orderId, loginShopId);

        if (shouldHit) {
            assertNotNull(order, caseName + "：应命中");
        } else {
            assertNull(order, caseName + "：不得命中");
            if ("OTHER_SHOP".equals(target)) {
                assertEquals("PENDING", reload(shopB_pending).getStatus(), "他店订单状态不得被读取篡改");
            }
        }
    }

    @Test
    void 商家更新_跨店零改动() {
        int affected = orderMapper.updateStatusByIdAndShopId(shopB_pending, "COMPLETED", SHOP_A);

        assertEquals(0, affected, "跨店更新应 0 行");
        assertEquals("PENDING", reload(shopB_pending).getStatus(), "他店订单状态必须原样");
    }

    @Test
    void 商家更新_本店生效() {
        int affected = orderMapper.updateStatusByIdAndShopId(shopA_pending, "PAID", SHOP_A);

        assertEquals(1, affected, "本店更新应影响 1 行");
        assertEquals("PAID", reload(shopA_pending).getStatus(), "本店订单状态应更新");
    }

    @Test
    void 买家查询_他人订单不可见() {
        assertNull(orderMapper.selectByIdAndBuyerUsername(shopA_buyerB, BUYER_A),
                "买家 A 不得读取买家 B 的订单");
    }

    @Test
    void 买家删除_他人订单零改动() {
        int affected = orderMapper.softDeleteByBuyer(shopA_buyerB, BUYER_A);

        assertEquals(0, affected, "删除他人订单应 0 行");
        assertEquals(0, reload(shopA_buyerB).getBuyerDeleted(), "他人订单不得被标记删除");
    }
}
