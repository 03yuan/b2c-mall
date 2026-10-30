package com.ysh.b2cmall.shop.web;

import com.ysh.b2cmall.shop.feign.OrderStatsClient;
import com.ysh.b2cmall.shop.feign.ProductStatsClient;
import com.ysh.b2cmall.shop.service.impl.DashboardServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * summary 接口满量计时验收（TDD 6.1.1）。
 * 对应 .tdd/task/shop-dashboard.md 功能上下文 6.1 任务项 6.1.1：
 * GET /shop/dashboard/summary（6 卡片 + 24 点趋势 + 4 指标）在满量数据下整体响应 < 1s
 * （Fail 边界：无索引全表扫描时超时，功能正确但验收不过）。
 * 满量口径：各状态订单合计 1 万条、sku 1 千条，建表 DDL 来自根目录 order.sql / product.sql。
 * Feign 测试替身直接对满量临时库执行与下游同构的真实 SQL，
 * 使计时覆盖「HTTP 入口 → 聚合 → 下游真实查询 → VO 组装」全链路。
 * 本测试为验收计时（被测物已实现），不做红绿循环。
 */
class DashboardSummaryTimingAcceptanceTest {

    private static final int ORDER_COUNT = 10000;
    private static final int SKU_COUNT = 1000;

    @TempDir
    File tempDir;

    private Connection orderConn;
    private Connection productConn;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        orderConn = DriverManager.getConnection(
                "jdbc:sqlite:" + new File(tempDir, "order-timing.db").getAbsolutePath());
        executeSchemaFile(orderConn, "order.sql");
        insertOrders(orderConn);

        productConn = DriverManager.getConnection(
                "jdbc:sqlite:" + new File(tempDir, "product-timing.db").getAbsolutePath());
        executeSchemaFile(productConn, "product.sql");
        insertSkus(productConn);

        OrderStatsClient orderStatsClient = new OrderStatsClient() {
            @Override
            public Map<String, Object> getStats(Integer shopId) {
                try {
                    return loadOrderStats(shopId);
                } catch (Exception e) {
                    throw new IllegalStateException("下游订单统计查询失败", e);
                }
            }
        };
        ProductStatsClient productStatsClient = new ProductStatsClient() {
            @Override
            public Map<String, Object> getStats(Integer shopId) {
                try {
                    Map<String, Object> stats = new HashMap<>();
                    stats.put("stockAlert", count(productConn,
                            "SELECT COUNT(*) FROM tb_sku WHERE shop_id = ? AND stock < stock_threshold", shopId));
                    return stats;
                } catch (Exception e) {
                    throw new IllegalStateException("下游商品统计查询失败", e);
                }
            }
        };
        mockMvc = MockMvcBuilders.standaloneSetup(
                new DashboardController(new DashboardServiceImpl(orderStatsClient, productStatsClient))).build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (orderConn != null) {
            orderConn.close();
        }
        if (productConn != null) {
            productConn.close();
        }
    }

    /** 下游订单统计（与 OrderMapper.xml 统计 SQL 同构）：状态计数 + 退款 + 今昨小时聚合 */
    private Map<String, Object> loadOrderStats(int shopId) throws Exception {
        Map<String, Object> stats = new HashMap<>();
        String[] statuses = {"PENDING", "PAID", "SHIPPED", "COMPLETED"};
        String[] keys = {"pendingPayment", "pendingShipment", "pendingReceipt", "completed"};
        for (int i = 0; i < statuses.length; i++) {
            stats.put(keys[i], count(orderConn,
                    "SELECT COUNT(*) FROM tb_order WHERE shop_id = ? AND status = '" + statuses[i] + "'", shopId));
        }
        stats.put("refundAfterSale", count(orderConn,
                "SELECT COUNT(*) FROM tb_refund WHERE shop_id = ? AND status = 'PROCESSING'", shopId));
        stats.put("todayHourly", hourly(orderConn, shopId, LocalDate.now()));
        stats.put("yesterdayHourly", hourly(orderConn, shopId, LocalDate.now().minusDays(1)));
        return stats;
    }

    /** 小时聚合（与 OrderMapper.xml sumPaidAmountByHour 同构），缺失小时补 0 */
    private List<BigDecimal> hourly(Connection conn, int shopId, LocalDate day) throws Exception {
        List<BigDecimal> series = new ArrayList<>(Collections.nCopies(24, BigDecimal.ZERO));
        String sql = "SELECT CAST(strftime('%H', pay_time) AS INTEGER) AS hour, SUM(total_price) AS amount "
                + "FROM tb_order WHERE shop_id = ? "
                + "AND pay_time >= ? AND pay_time < ? "
                + "AND status IN ('PAID', 'SHIPPED', 'COMPLETED') "
                + "GROUP BY strftime('%H', pay_time)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            ps.setString(2, day + " 00:00:00");
            ps.setString(3, day.plusDays(1) + " 00:00:00");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    series.set(rs.getInt("hour"), rs.getBigDecimal("amount"));
                }
            }
        }
        return series;
    }

    private int count(Connection conn, String sql, int shopId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, shopId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
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

    /** 满量 1 万订单（两店各 5000、四状态均匀、支付时间跨今昨两天）+ 1000 退款单 */
    private void insertOrders(Connection conn) throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        String[] statuses = {"PENDING", "PAID", "SHIPPED", "COMPLETED"};
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                        + " status, pay_time, buyer_username, receiver_name, receiver_phone, receiver_address)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (int i = 0; i < ORDER_COUNT; i++) {
                String status = statuses[(i / 2) % 4];
                String day = ((i / 2) % 2 == 0) ? today.toString() : yesterday.toString();
                ps.setString(1, "NO-" + i);
                ps.setInt(2, (i % 2) + 1);
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

    /** 满量 1000 sku（两店各 500，一半预警） */
    private void insertSkus(Connection conn) throws Exception {
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tb_sku (shop_id, category_id, name, price, stock, stock_threshold, type)"
                        + " VALUES (?,?,?,?,?,?,?)")) {
            for (int i = 0; i < SKU_COUNT; i++) {
                ps.setInt(1, (i % 2) + 1);
                ps.setInt(2, 1);
                ps.setString(3, "测试商品 " + i);
                ps.setBigDecimal(4, new BigDecimal("9.90"));
                ps.setInt(5, (i / 2) % 2 == 0 ? 1 : 100);
                ps.setInt(6, 10);
                ps.setInt(7, 1);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        conn.commit();
        conn.setAutoCommit(true);
    }

    @Test
    void summary接口_满量数据整体响应小于1s() throws Exception {
        long start = System.nanoTime();
        MvcResult result = mockMvc.perform(get("/shop/dashboard/summary")
                        .header("Authorization", "Bearer timing-token")
                        .header("X-Shop-Id", "1"))
                .andExpect(status().isOk())
                .andReturn();
        long ms = (System.nanoTime() - start) / 1_000_000;

        String body = result.getResponse().getContentAsString();
        assertNotNull(body, "响应体应非空");
        assertTrue(body.contains("shopId"), "响应应含聚合 VO 字段");

        System.out.println("[6.1.1] summary 整体响应耗时 " + ms + "ms");
        assertTrue(ms < 1000,
                "summary 整体响应耗时 " + ms + "ms 超过 1000ms（Fail 边界：无索引全表扫描时超时，验收不过）");
    }
}
