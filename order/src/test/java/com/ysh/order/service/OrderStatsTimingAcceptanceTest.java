package com.ysh.order.service;

import com.ysh.order.dao.OrderMapper;
import com.ysh.order.dao.po.MetricsPO;
import com.ysh.order.service.impl.OrderStatsServiceImpl;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下游统计链路满量计时验收（TDD 6.1.2）。
 * 对应 .tdd/task/shop-dashboard.md 功能上下文 6.1 任务项 6.1.2：
 * order-service 的状态计数 / 小时聚合 / 指标整条统计链路在真实库端到端计时，
 * 单次 < 300ms 为聚合留余量（Fail 边界：单下游就超 600ms 时聚合必然超标）。
 * 建表 DDL 来自根目录 order.sql（含 6.2 组合索引），满量 1 万订单 + 1000 退款单。
 * 本测试为验收计时（被测物已实现），只断言耗时与链路真实执行，不做红绿循环。
 *
 * 测试库为 @TempDir 下的临时 SQLite 库（绝对路径连接串），MyBatis 绑定真实 OrderMapper.xml。
 */
class OrderStatsTimingAcceptanceTest {

    private static final int ROW_COUNT = 10000;

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private OrderMapper orderMapper;
    private OrderStatsService statsService;
    /** 期望值跟踪：店铺 1 的 PENDING 计数 */
    private int expectedPendingShop1;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "order-timing.db").getAbsolutePath();
        dataSource = new UnpooledDataSource("org.sqlite.JDBC", jdbcUrl, null, null);

        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        try (InputStream in = Resources.getResourceAsStream("mapper/OrderMapper.xml")) {
            new XMLMapperBuilder(in, configuration, "mapper/OrderMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        session = sqlSessionFactory.openSession(true);
        orderMapper = session.getMapper(OrderMapper.class);
        statsService = new OrderStatsServiceImpl(orderMapper);

        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            executeSchemaFile(conn, "order.sql");
            insertData(conn);
        }
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    /** 执行根目录建表脚本（含 6.2 组合索引） */
    private void executeSchemaFile(Connection conn, String fileName) throws Exception {
        File file = new File("../" + fileName);
        if (!file.exists()) {
            file = new File(fileName);
        }
        assertTrue(file.exists(), "找不到建表脚本 " + fileName);
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        StringBuilder noComments = new StringBuilder();
        for (String line : text.split("\r?\n")) {
            if (!line.trim().startsWith("--")) {
                noComments.append(line).append('\n');
            }
        }
        for (String stmt : noComments.toString().split(";")) {
            if (stmt.replaceAll("--.*", "").trim().isEmpty()) {
                continue;
            }
            try (Statement st = conn.createStatement()) {
                st.execute(stmt);
            }
        }
    }

    /** 满量数据：1 万订单（两店各 5000、四状态均匀、支付时间跨今昨两天）+ 1000 退款单 */
    private void insertData(Connection conn) throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        String[] statuses = {"PENDING", "PAID", "SHIPPED", "COMPLETED"};
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                        + " status, pay_time, buyer_username, receiver_name, receiver_phone, receiver_address)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (int i = 0; i < ROW_COUNT; i++) {
                int shopId = (i % 2) + 1;
                String status = statuses[(i / 2) % 4];
                String day = ((i / 2) % 2 == 0) ? today.toString() : yesterday.toString();
                ps.setString(1, "NO-" + i);
                ps.setInt(2, shopId);
                ps.setInt(3, 1);
                ps.setString(4, "测试商品");
                ps.setInt(5, 1);
                ps.setBigDecimal(6, new BigDecimal("9.90"));
                ps.setString(7, status);
                ps.setString(8, "PENDING".equals(status) ? null
                        : day + String.format(" %02d:00:00", (i / 2) % 24));
                ps.setString(9, "buyer-" + (i % 500));
                ps.setString(10, "张三");
                ps.setString(11, "13800000000");
                ps.setString(12, "测试地址");
                ps.addBatch();

                if (shopId == 1 && "PENDING".equals(status)) {
                    expectedPendingShop1++;
                }
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tb_refund (order_id, shop_id, status, amount, create_time) VALUES (?,?,?,?,?)")) {
            for (int i = 0; i < 1000; i++) {
                ps.setInt(1, i);
                ps.setInt(2, (i % 2) + 1);
                ps.setString(3, i % 2 == 0 ? "PROCESSING" : "DONE");
                ps.setBigDecimal(4, new BigDecimal("5.00"));
                ps.setString(5, today + " 10:00:00");
                ps.addBatch();
            }
            ps.executeBatch();
        }
        conn.commit();
        conn.setAutoCommit(true);
    }

    @Test
    void 下游统计链路_满量计时小于300ms() {
        LocalDate today = LocalDate.now();

        long start = System.nanoTime();
        int pending = orderMapper.countByShopIdAndStatus(1, "PENDING");
        orderMapper.countByShopIdAndStatus(1, "PAID");
        orderMapper.countByShopIdAndStatus(1, "SHIPPED");
        orderMapper.countByShopIdAndStatus(1, "COMPLETED");
        orderMapper.countOngoingRefundsByShopId(1);
        List<BigDecimal> todaySeries = statsService.hourlyPaidAmounts(1, today);
        List<BigDecimal> yesterdaySeries = statsService.hourlyPaidAmounts(1, today.minusDays(1));
        MetricsPO metrics = statsService.dailyMetrics(1, today);
        long ms = (System.nanoTime() - start) / 1_000_000;

        // 链路真实执行的轻量校验（口径正确性由 2.1/3.1/4.1 测试保障）
        assertEquals(expectedPendingShop1, pending, "状态计数口径");
        assertNotNull(todaySeries, "今日小时序列应有数据");
        assertEquals(24, todaySeries.size(), "今日序列长度");
        assertNotNull(yesterdaySeries, "昨日小时序列应有数据");
        assertTrue(metrics.getPayOrders() > 0, "指标链路应真实执行");

        System.out.println("[6.1.2] order 下游统计链路耗时 " + ms + "ms");
        assertTrue(ms < 300,
                "下游统计链路耗时 " + ms + "ms 超过 300ms（Fail 边界：单下游超 600ms 时聚合必然超标）");
    }
}
