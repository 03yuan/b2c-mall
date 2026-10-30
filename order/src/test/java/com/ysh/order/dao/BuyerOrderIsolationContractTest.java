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
 * 买家侧数据隔离的 DAO 真库契约测试（回归护栏，直接绿）。
 * 对应 .tdd/task/mall-order-manage.md 功能上下文 2.2（Controller + DAO 层 - 隔离条件硬编码）：
 * - 2.2.1 买家侧查询/更新 SQL 恒带 buyer_username = 登录 username AND buyer_deleted = 0
 *   （复用 buyerQueryWhere 共用片段，username 为 null 不得退化为全表查询）
 *   （Fail 边界：漏 buyer_deleted -> 已删除订单复活；漏 buyer_username -> 看到他人订单）
 * - 2.2.2 跨身份 id 防护：写操作传入他人/不存在/已删 orderId -> 零改动（404 级）
 *   （Fail 边界：UPDATE 漏身份条件 -> 改到他人数据）
 *
 * 本测试聚焦「写操作（逻辑删除）的跨身份零改动矩阵」+「已删订单不可见」，对
 * softDeleteByBuyer / selectByIdAndBuyerUsername 两条隔离 SQL 做独立回归锁定，
 * 与 OrderIsolationTest / BuyerOrderMapperTest 互补（后者聚焦商家侧 + 列表/计数口径）。
 *
 * 底层实现已在 shop-order-list.md 2.3 / 11.1 落地，本测试为回归护栏直接绿，
 * 防止后续 SQL 改动把「buyer_username + buyer_deleted=0 恒置」回归为透传。
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：本人订单查询/删除命中且字段完整
 * 2. 类型与格式：username 大小写/空格敏感；已删订单不可见
 * 3. 重复创建：重复查询一致；重复逻辑删除幂等
 * 4. 非法输入：他人/不存在/已删订单作为写操作目标均零改动
 */
class BuyerOrderIsolationContractTest {

    private static final String BUYER_A = "buyer_a";
    private static final String BUYER_B = "buyer_b";

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private OrderMapper orderMapper;
    private int orderNoSeq = 0;

    private int aPending;     // 买家 A 未删 PENDING
    private int aDeleted;     // 买家 A 已删 PENDING
    private int bPending;     // 买家 B 未删 PENDING

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "buyer-isolation-contract.db").getAbsolutePath();
        dataSource = new UnpooledDataSource("org.sqlite.JDBC", jdbcUrl, null, null);

        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
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
                    + "order_type VARCHAR(20) DEFAULT 'NORMAL',"
                    + "order_category VARCHAR(20) DEFAULT 'NORMAL',"
                    + "logistics_type VARCHAR(20),"
                    + "logistics_no VARCHAR(64),"
                    + "store_name VARCHAR(100),"
                    + "shop_name VARCHAR(100),"
                    + "sku_image VARCHAR(500),"
                    + "unit_price DECIMAL(10,2),"
                    + "is_gift INTEGER DEFAULT 0,"
                    + "create_time DATETIME DEFAULT (datetime('now','localtime')),"
                    + "update_time DATETIME DEFAULT (datetime('now','localtime')))");
        }

        session = sqlSessionFactory.openSession(true);
        orderMapper = session.getMapper(OrderMapper.class);

        aPending = insertOrder(BUYER_A, "PENDING", 0);
        aDeleted = insertOrder(BUYER_A, "PENDING", 1);
        bPending = insertOrder(BUYER_B, "PENDING", 0);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private int insertOrder(String buyerUsername, String status, int buyerDeleted) throws Exception {
        orderNoSeq++;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                             + " status, receiver_name, receiver_phone, receiver_address,"
                             + " buyer_username, buyer_deleted)"
                             + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "NO-" + orderNoSeq);
            ps.setInt(2, 1);
            ps.setInt(3, 100);
            ps.setString(4, "测试商品");
            ps.setInt(5, 2);
            ps.setBigDecimal(6, new java.math.BigDecimal("39.80"));
            ps.setString(7, status);
            ps.setString(8, "张三");
            ps.setString(9, "13800001111");
            ps.setString(10, "上海市浦东新区测试路 1 号");
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

    // ============ 1. 字段级校验：本人查询/删除命中且字段完整 ============

    @Test
    void 本人订单查询_命中且字段完整() {
        OrderPO order = orderMapper.selectByIdAndBuyerUsername(aPending, BUYER_A);

        assertNotNull(order, "本人未删订单应可查");
        assertEquals(BUYER_A, order.getBuyerUsername(), "buyerUsername 正确");
        assertEquals("PENDING", order.getStatus(), "status 正确");
        assertEquals("NO-" + aPending, order.getOrderNo(), "orderNo 正确");
    }

    @Test
    void 本人订单删除_影响一行() {
        int affected = orderMapper.softDeleteByBuyer(aPending, BUYER_A);

        assertEquals(1, affected, "删除本人未删订单应影响 1 行");
        assertEquals(1, reload(aPending).getBuyerDeleted(), "删除后 buyer_deleted 应为 1");
    }

    // ============ 2. 类型与格式：username 边界 + 已删订单不可见 ============

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
        OrderPO order = orderMapper.selectByIdAndBuyerUsername(aPending, username);

        if (shouldHit) {
            assertNotNull(order, caseName + "：应命中本人订单");
        } else {
            assertNull(order, caseName + "：不得命中");
        }
    }

    @Test
    void 已删订单_查询不可见() {
        assertNull(orderMapper.selectByIdAndBuyerUsername(aDeleted, BUYER_A),
                "buyer_deleted=1 的订单不得出现在买家侧");
    }

    // ============ 3. 重复创建：重复查询一致 / 重复删除幂等 ============

    @Test
    void 重复查询_结果一致() {
        OrderPO first = orderMapper.selectByIdAndBuyerUsername(aPending, BUYER_A);
        OrderPO second = orderMapper.selectByIdAndBuyerUsername(aPending, BUYER_A);

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.getOrderNo(), second.getOrderNo(), "重复查询 orderNo 应一致");
        assertEquals(first.getStatus(), second.getStatus(), "重复查询 status 应一致");
    }

    @Test
    void 重复逻辑删除_第二次零行且不复活() {
        int first = orderMapper.softDeleteByBuyer(aPending, BUYER_A);
        int second = orderMapper.softDeleteByBuyer(aPending, BUYER_A);

        assertEquals(1, first, "首次删除应影响 1 行");
        assertEquals(0, second, "重复删除应幂等（0 行）");
        assertEquals(1, reload(aPending).getBuyerDeleted(), "订单应保持已删除标记");
    }

    // ============ 4. 非法输入：他人/不存在/已删 作为写操作目标均零改动 ============

    /**
     * 列：用例名、目标 orderId 语义、登录 username、期望受影响行数
     */
    static Stream<Arguments> crossIdentityDeleteCases() {
        return Stream.of(
                arguments("删除他人订单零改动", "OTHER", BUYER_A, 0),
                arguments("删除不存在订单零改动", "NOT_EXISTS", BUYER_A, 0),
                arguments("删除已删订单零改动", "DELETED", BUYER_A, 0)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("crossIdentityDeleteCases")
    void 买家删除_跨身份零改动(String caseName, String target, String username, int expectedAffected) {
        int orderId = switch (target) {
            case "OTHER" -> bPending;
            case "NOT_EXISTS" -> 99999;
            default -> aDeleted;
        };

        int affected = orderMapper.softDeleteByBuyer(orderId, username);

        assertEquals(expectedAffected, affected, caseName + "：受影响行数不正确");
    }

    @Test
    void 删除他人订单_目标数据原样() {
        orderMapper.softDeleteByBuyer(bPending, BUYER_A);

        OrderPO order = reload(bPending);
        assertEquals(0, order.getBuyerDeleted(), "他人订单 buyer_deleted 不得被改动");
        assertEquals("PENDING", order.getStatus(), "他人订单 status 不得被改动");
    }

    @Test
    void 删除已删订单_保持已删且不影响其它字段() {
        orderMapper.softDeleteByBuyer(aDeleted, BUYER_A);

        OrderPO order = reload(aDeleted);
        assertEquals(1, order.getBuyerDeleted(), "已删订单保持 buyer_deleted=1");
        assertEquals(BUYER_A, order.getBuyerUsername(), "buyerUsername 原样");
        assertEquals("PENDING", order.getStatus(), "status 原样");
    }
}
