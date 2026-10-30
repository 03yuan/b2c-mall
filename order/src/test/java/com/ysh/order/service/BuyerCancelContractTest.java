package com.ysh.order.service;

import com.ysh.order.dao.OrderLogMapper;
import com.ysh.order.dao.OrderMapper;
import com.ysh.order.dao.po.OrderPO;
import com.ysh.order.service.impl.OrderServiceImpl;
import com.ysh.order.statemachine.OrderStateFactory;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;

/**
 * 买家取消订单（cancelByBuyer）错误文案契约测试（TDD 回归保障）。
 * 对应 .tdd/task/mall-order-manage.md 功能上下文 9.1（待付款 -> 买家取消订单【已实现·回归】）：
 * - 9.1.2 PAID 取消 -> 400 级中文错误提示「已付款订单请申请退款」；他人 -> 404 零改动
 *
 * 契约锁定点：用真实状态机（非 mock）跑 cancelByBuyer，锁定真实错误文案，
 * 防止状态机消息被误改后「取消引导走退款」的契约被静默破坏（现有 Controller 测试用 mock 假消息，未锁定真实文案）。
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：PENDING 取消成功 -> FAILED（回归 9.1.1）
 * 2. 类型与格式：PAID 取消错误文案含「请申请退款」
 * 3. 重复取消：FAILED 重复取消 -> 文案含「不能重复取消」
 * 4. 非法输入：SHIPPED/COMPLETED 取消错误文案正确；他人订单 -> 404 零改动
 */
class BuyerCancelContractTest {

    private static final String BUYER_A = "buyer_a";
    private static final String BUYER_B = "buyer_b";

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private OrderMapper orderMapper;
    private OrderLogMapper orderLogMapper;
    private OrderServiceImpl orderService;
    private int orderNoSeq = 0;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "buyer-cancel-contract.db").getAbsolutePath();
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
        orderLogMapper = mock(OrderLogMapper.class);

        orderService = new OrderServiceImpl();
        ReflectionTestUtils.setField(orderService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(orderService, "orderLogMapper", orderLogMapper);
        ReflectionTestUtils.setField(orderService, "stateFactory", new OrderStateFactory());
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private int insertOrder(String buyerUsername, String status) throws Exception {
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
            ps.setInt(12, 0);
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

    // ============ 1. 字段级校验：PENDING 取消成功 -> FAILED（回归 9.1.1） ============

    @Test
    void 待付款订单取消_状态为FAILED() throws Exception {
        int id = insertOrder(BUYER_A, "PENDING");

        orderService.cancelByBuyer(id, BUYER_A);

        assertEquals("FAILED", reload(id).getStatus(), "待付款取消后状态应为 FAILED");
    }

    // ============ 2. 类型与格式：PAID 取消错误文案含「请申请退款」 ============

    @Test
    void 已付款订单取消_错误文案含请申请退款() throws Exception {
        int id = insertOrder(BUYER_A, "PAID");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> orderService.cancelByBuyer(id, BUYER_A),
                "已付款订单取消应抛 IllegalStateException");

        assertTrue(ex.getMessage() != null && ex.getMessage().contains("请申请退款"),
                "错误文案应引导用户申请退款，实际: " + ex.getMessage());
        assertEquals("PAID", reload(id).getStatus(), "已付款订单状态不得被改动");
    }

    // ============ 3. 重复取消：FAILED 重复取消文案含「不能重复取消」 ============

    @Test
    void 交易失败订单重复取消_文案含不能重复取消() throws Exception {
        int id = insertOrder(BUYER_A, "FAILED");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> orderService.cancelByBuyer(id, BUYER_A),
                "FAILED 终态重复取消应抛 IllegalStateException");

        assertTrue(ex.getMessage() != null && ex.getMessage().contains("不能重复取消"),
                "重复取消文案应明确，实际: " + ex.getMessage());
    }

    // ============ 4. 非法输入：SHIPPED/COMPLETED 取消文案正确 + 他人 404 零改动 ============

    /**
     * 列：用例名、订单状态、期望文案关键词
     */
    static Stream<Arguments> notCancellableContractCases() {
        return Stream.of(
                arguments("已发货", "SHIPPED", "请申请退款"),
                arguments("已完成", "COMPLETED", "不能取消")
        );
    }

    @ParameterizedTest(name = "{0} 订单取消")
    @MethodSource("notCancellableContractCases")
    void 已发货或已完成订单取消_错误文案正确且零改动(String caseName, String status, String keyword) throws Exception {
        int id = insertOrder(BUYER_A, status);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> orderService.cancelByBuyer(id, BUYER_A),
                caseName + "订单取消应抛 IllegalStateException");

        assertTrue(ex.getMessage() != null && ex.getMessage().contains(keyword),
                caseName + "取消文案应含「" + keyword + "」，实际: " + ex.getMessage());
        assertEquals(status, reload(id).getStatus(), caseName + "订单状态不得被改动");
    }

    @Test
    void 取消他人订单_抛订单不存在且零改动() throws Exception {
        int otherId = insertOrder(BUYER_B, "PENDING");

        assertThrows(IllegalArgumentException.class,
                () -> orderService.cancelByBuyer(otherId, BUYER_A),
                "取消他人订单应抛订单不存在");

        assertEquals("PENDING", reload(otherId).getStatus(), "他人订单状态不得被改动");
    }
}
