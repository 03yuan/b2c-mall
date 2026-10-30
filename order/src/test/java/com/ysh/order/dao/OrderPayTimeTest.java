package com.ysh.order.dao;

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

import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 余额支付的 pay_time 落库契约的真实 SQLite 测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 4.1：
 * - 4.1.1 支付成功时 pay_type=BALANCE 且 pay_time 非空
 *   （Fail 边界：updateStatus 只改 status 与 pay_type、不写 pay_time -> 支付时间恒空，
 *    详情页无支付时间、小时趋势统计恒为 0）
 * - 非支付流转（发货/确认收货）不得覆盖支付时间
 *
 * 用例分类：
 * 1. 字段级校验：pay_type 与 pay_time 同时落库
 * 2. 类型与格式：pay_time 为可解析的日期时间（非空字符串占位）
 * 3. 重复创建：支付后再发货，pay_time 保持不变
 * 4. 非法输入：未支付订单 pay_time 为 null（不伪造默认值）
 */
class OrderPayTimeTest {

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private OrderMapper orderMapper;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "order-pay-time.db").getAbsolutePath();
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
                    + "create_time DATETIME DEFAULT (datetime('now','localtime')),"
                    + "update_time DATETIME DEFAULT (datetime('now','localtime')))");
        }

        session = sqlSessionFactory.openSession(true);
        orderMapper = session.getMapper(OrderMapper.class);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private int insertOrder(String status) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                             + " status, receiver_name, receiver_phone, receiver_address)"
                             + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, "NO-PAY-1");
            ps.setInt(2, 1);
            ps.setInt(3, 10);
            ps.setString(4, "测试商品");
            ps.setInt(5, 1);
            ps.setBigDecimal(6, new BigDecimal("99.00"));
            ps.setString(7, status);
            ps.setString(8, "张三");
            ps.setString(9, "13800005678");
            ps.setString(10, "上海市浦东新区");
            ps.executeUpdate();
        }
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM tb_order WHERE order_no = 'NO-PAY-1'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** 返回 {status, pay_type, pay_time} */
    private String[] readPay(String orderId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT status, pay_type, pay_time FROM tb_order WHERE id = ?")) {
            ps.setInt(1, Integer.parseInt(orderId));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new String[]{rs.getString("status"), rs.getString("pay_type"), rs.getString("pay_time")};
            }
        }
    }

    // ============ 1/2. 字段级校验 + 类型与格式：支付落库 ============

    @Test
    void 余额支付_pay_type为BALANCE且pay_time非空() throws Exception {
        int orderId = insertOrder("PENDING");

        orderMapper.updateStatus(orderId, "PAID", "BALANCE");

        String[] pay = readPay(String.valueOf(orderId));
        assertEquals("PAID", pay[0], "状态应落库为 PAID");
        assertEquals("BALANCE", pay[1], "pay_type 应落库为 BALANCE");
        assertNotNull(pay[2], "pay_time 不得为空（支付时间缺失会让详情页无支付时间、小时趋势恒为 0）");
        // 类型与格式：SQLite datetime('now','localtime') 形如 2026-10-27 10:20:30
        assertTrue(pay[2].matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "pay_time 应为可解析的日期时间，实际：" + pay[2]);
    }

    // ============ 3. 重复创建：支付后发货不覆盖 pay_time ============

    @Test
    void 支付后发货_pay_time保持不变() throws Exception {
        int orderId = insertOrder("PENDING");

        orderMapper.updateStatus(orderId, "PAID", "BALANCE");
        String payTimeAfterPay = readPay(String.valueOf(orderId))[2];
        orderMapper.updateStatus(orderId, "SHIPPED", null);

        String[] afterShip = readPay(String.valueOf(orderId));
        assertEquals("SHIPPED", afterShip[0], "状态应为 SHIPPED");
        assertEquals("BALANCE", afterShip[1], "发货不得清空已记录的支付方式");
        assertEquals(payTimeAfterPay, afterShip[2], "发货不得覆盖支付时间");
    }

    // ============ 4. 非法输入：未支付订单不伪造 pay_time ============

    @Test
    void 未支付订单_pay_time为空() throws Exception {
        int orderId = insertOrder("PENDING");

        String[] pay = readPay(String.valueOf(orderId));

        assertNull(pay[1], "未支付订单 pay_type 应为 null");
        assertNull(pay[2], "未支付订单 pay_time 应为 null（不得填充默认时间）");
    }
}
