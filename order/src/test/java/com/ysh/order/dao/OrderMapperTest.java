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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * OrderMapper 统计计数 SQL 的真实 SQLite 测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-dashboard.md 功能上下文 2.1（order-service 数据访问层）：
 * - 2.1.1 按 shopId 分状态计数：待付款/待发货/待收货/已完成四个状态各自的订单总数
 *   （Fail 边界：状态过滤条件写错时计数串位）
 * - 2.1.2 退款/售后计数：进行中的退款申请单据总数（shopId 维度）
 *   （Fail 边界：不按「进行中」过滤把历史退款也算入）
 * - 2.1.3 订单查询统一带 shopId 条件（不可跨店）
 *   （Fail 边界：漏 shopId 条件）
 *
 * 用例分类（表格驱动，适配 DAO 计数行为）：
 * 1. 字段级校验：四个状态计数互不串位；退款只计进行中
 * 2. 类型与格式：状态值精确匹配（大小写/空格敏感）；无数据计 0（int 非 null）
 * 3. 重复创建：同状态重复插入订单/退款单，计数累加
 * 4. 非法输入：未知/空状态计 0；跨店数据互不可见（shopId 隔离）
 *
 * 测试库为 @TempDir 下的临时 SQLite 库（绝对路径连接串）。
 * tb_refund（退款申请单据表）为本功能新增表，绿阶段需同步写入 order.sql。
 */
class OrderMapperTest {

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private OrderMapper orderMapper;
    private int orderNoSeq = 0;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "order-test.db").getAbsolutePath();
        dataSource = new UnpooledDataSource("org.sqlite.JDBC", jdbcUrl, null, null);

        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
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
                    + "receiver_name VARCHAR(50) NOT NULL,"
                    + "receiver_phone VARCHAR(20) NOT NULL,"
                    + "receiver_address VARCHAR(500) NOT NULL,"
                    + "create_time DATETIME DEFAULT (datetime('now','localtime')),"
                    + "update_time DATETIME DEFAULT (datetime('now','localtime')))");
            st.executeUpdate("DROP TABLE IF EXISTS tb_refund");
            st.executeUpdate("CREATE TABLE tb_refund ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "order_id INTEGER NOT NULL,"
                    + "shop_id INTEGER NOT NULL,"
                    + "status VARCHAR(20) NOT NULL,"
                    + "create_time DATETIME DEFAULT (datetime('now','localtime')))");
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

    private void insertOrder(int shopId, String status) throws Exception {
        orderNoSeq++;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                             + " status, receiver_name, receiver_phone, receiver_address)"
                             + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, "NO-" + orderNoSeq);
            ps.setInt(2, shopId);
            ps.setInt(3, 1);
            ps.setString(4, "测试商品");
            ps.setInt(5, 1);
            ps.setBigDecimal(6, new BigDecimal("9.90"));
            ps.setString(7, status);
            ps.setString(8, "张三");
            ps.setString(9, "13800000000");
            ps.setString(10, "测试地址");
            ps.executeUpdate();
        }
    }

    private void insertRefund(int shopId, String status) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_refund (order_id, shop_id, status) VALUES (?,?,?)")) {
            ps.setInt(1, 0);
            ps.setInt(2, shopId);
            ps.setString(3, status);
            ps.executeUpdate();
        }
    }

    // ============ 2.1.1 按 shopId 分状态计数（表格驱动） ============

    /**
     * 列：用例名、店铺 1 插入的订单状态列表（逗号分隔）、查询状态、期望计数
     */
    static Stream<Arguments> countByStatusCases() {
        return Stream.of(
                // ----1. 字段级校验：四个状态计数互不串位----
                arguments("四状态各1条-PENDING计1", "PENDING,PAID,SHIPPED,COMPLETED", "PENDING", 1),
                arguments("四状态各1条-PAID计1", "PENDING,PAID,SHIPPED,COMPLETED", "PAID", 1),
                arguments("四状态各1条-SHIPPED计1", "PENDING,PAID,SHIPPED,COMPLETED", "SHIPPED", 1),
                arguments("四状态各1条-COMPLETED计1", "PENDING,PAID,SHIPPED,COMPLETED", "COMPLETED", 1),

                // ----2. 类型与格式：状态值精确匹配、无数据计 0----
                arguments("状态值小写不匹配", "PENDING", "pending", 0),
                arguments("状态值含空格不匹配", "PENDING", "PENDING ", 0),
                arguments("无订单计0", "", "PENDING", 0),

                // ----3. 重复创建：同状态重复插入计数累加----
                arguments("重复插入3条PAID计3", "PAID,PAID,PAID", "PAID", 3),
                arguments("重复插入5条PENDING计5", "PENDING,PENDING,PENDING,PENDING,PENDING", "PENDING", 5),

                // ----4. 非法输入：未知/空状态计 0（跨店隔离见单独用例）----
                arguments("未知状态计0", "PENDING,PAID", "REFUNDED", 0),
                arguments("状态为空串计0", "PENDING", "", 0)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("countByStatusCases")
    void countByShopIdAndStatus(String caseName, String statuses, String queryStatus, int expected) throws Exception {
        for (String status : statuses.split(",")) {
            if (!status.isEmpty()) {
                insertOrder(1, status);
            }
        }

        assertEquals(expected, orderMapper.countByShopIdAndStatus(1, queryStatus));
    }

    // ============ 2.1.2 退款/售后计数：只计进行中的退款申请单据（表格驱动） ============

    /**
     * 列：用例名、店铺 1 插入的退款单状态列表（PROCESSING=进行中，DONE=已完成）、期望计数
     */
    static Stream<Arguments> countOngoingRefundCases() {
        return Stream.of(
                // ----1. 字段级校验：只计进行中----
                arguments("2条进行中1条已完成计2", "PROCESSING,PROCESSING,DONE", 2),

                // ----2. 类型与格式：无单据计 0----
                arguments("无退款单计0", "", 0),

                // ----3. 重复创建：重复提交进行中单据计数累加----
                arguments("重复提交5条进行中计5", "PROCESSING,PROCESSING,PROCESSING,PROCESSING,PROCESSING", 5),

                // ----4. 非法输入：已完成/未知状态不计入（跨店隔离见单独用例）----
                arguments("已完成不计入", "DONE,DONE", 0),
                arguments("未知状态不计入", "PROCESSING,UNKNOWN,DONE", 1)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("countOngoingRefundCases")
    void countOngoingRefundsByShopId(String caseName, String refundStatuses, int expected) throws Exception {
        for (String status : refundStatuses.split(",")) {
            if (!status.isEmpty()) {
                insertRefund(1, status);
            }
        }

        assertEquals(expected, orderMapper.countOngoingRefundsByShopId(1));
    }

    // ============ 2.1.3 shopId 隔离：不可跨店 ============

    @Test
    void countByShopIdAndStatus_跨店隔离_各计3而非6() throws Exception {
        for (String status : new String[]{"PENDING", "PAID", "SHIPPED"}) {
            insertOrder(1, status);
            insertOrder(2, status);
        }

        for (int shopId : new int[]{1, 2}) {
            int total = orderMapper.countByShopIdAndStatus(shopId, "PENDING")
                    + orderMapper.countByShopIdAndStatus(shopId, "PAID")
                    + orderMapper.countByShopIdAndStatus(shopId, "SHIPPED")
                    + orderMapper.countByShopIdAndStatus(shopId, "COMPLETED");
            assertEquals(3, total, "店铺 " + shopId + " 应只统计到自己的 3 条订单而非全站 6 条");
            assertEquals(1, orderMapper.countByShopIdAndStatus(shopId, "PENDING"));
        }
    }

    @Test
    void countOngoingRefundsByShopId_跨店隔离_只计自己的() throws Exception {
        insertRefund(1, "PROCESSING");
        insertRefund(2, "PROCESSING");
        insertRefund(2, "PROCESSING");

        assertEquals(1, orderMapper.countOngoingRefundsByShopId(1));
        assertEquals(2, orderMapper.countOngoingRefundsByShopId(2));
    }
}
