package com.ysh.order.dao;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * tb_order 组合索引的 DAO 层性能测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-dashboard.md 功能上下文 6.2（索引先行）：
 * - 6.2.1 tb_order 建组合索引（shop_id+status、shop_id+pay_time），
 *   支撑状态计数与小时聚合走索引
 *   （Fail 边界：只有 shop_id 单列索引时按 status/pay_time 过滤仍会回表扫描）
 * 建表与索引 DDL 全部来自根目录 order.sql（被测物即建表脚本本身）：
 * 测试先执行该脚本建库，再灌满量 1 万条订单验证索引命中与计时。
 *
 * 用例分类（表格驱动，适配 DAO 索引保障）：
 * 1. 字段级校验：统计 SQL 命中的组合索引列齐全且 shop_id 在前（单列 shop_id 不达标）
 * 2. 类型与格式：满量 1 万条数据下统计 SQL 计时 < 100ms
 * 3. 重复创建：查询无创建语义 -> 重复执行结果一致（幂等，见独立测试）
 * 4. 非法输入：跨店统计互不可见且各自走索引（见独立测试）
 *
 * 测试库为 @TempDir 下的临时 SQLite 库（绝对路径连接串）。
 */
class OrderStatsIndexTest {

    private static final int ROW_COUNT = 10000;

    /** 与 OrderMapper.xml countByShopIdAndStatus 同构（参数字面化） */
    private static final String COUNT_SQL =
            "SELECT COUNT(*) FROM tb_order WHERE shop_id = 1 AND status = 'PENDING'";

    /** 与 OrderMapper.xml sumPaidAmountByHour 同构（参数字面化） */
    private static final String HOUR_SQL =
            "SELECT CAST(strftime('%H', pay_time) AS INTEGER) AS hour, SUM(total_price) AS amount "
                    + "FROM tb_order WHERE shop_id = 1 "
                    + "AND pay_time >= '2026-10-23 00:00:00' AND pay_time < '2026-10-24 00:00:00' "
                    + "AND status IN ('PAID', 'SHIPPED', 'COMPLETED') "
                    + "GROUP BY strftime('%H', pay_time)";

    /** 与 OrderMapper.xml countPaidOrders 同构（参数字面化） */
    private static final String PAID_COUNT_SQL =
            "SELECT COUNT(*) FROM tb_order WHERE shop_id = 1 "
                    + "AND pay_time >= '2026-10-23 00:00:00' AND pay_time < '2026-10-24 00:00:00' "
                    + "AND status IN ('PAID', 'SHIPPED', 'COMPLETED')";

    @TempDir
    File tempDir;

    private Connection conn;
    /** 期望值跟踪：shopId|status -> 计数 */
    private final Map<String, Integer> statusCount = new HashMap<>();
    /** 期望值跟踪：shopId -> 今日支付订单数 */
    private final Map<Integer, Integer> paidCount = new HashMap<>();
    /** 期望值跟踪：shopId -> 今日支付金额 */
    private final Map<Integer, BigDecimal> paidAmount = new HashMap<>();
    /** 期望值跟踪：shopId -> 有支付的小时集合 */
    private final Map<Integer, Set<Integer>> paidHours = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "order-index-test.db").getAbsolutePath();
        conn = DriverManager.getConnection(jdbcUrl);
        executeSchemaFile("order.sql");
        insertOrders();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null) {
            conn.close();
        }
    }

    /** 执行根目录建表脚本（DDL 被测物） */
    private void executeSchemaFile(String fileName) throws Exception {
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

    /** 满量 1 万条订单（两店各 5000，四状态均匀），并同步跟踪期望值 */
    private void insertOrders() throws Exception {
        String[] statuses = {"PENDING", "PAID", "SHIPPED", "COMPLETED"};
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                        + " status, pay_time, receiver_name, receiver_phone, receiver_address)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            for (int i = 0; i < ROW_COUNT; i++) {
                int shopId = (i % 2) + 1;
                String status = statuses[(i / 2) % 4];
                int hour = (i / 2) % 24;
                ps.setString(1, "NO-" + i);
                ps.setInt(2, shopId);
                ps.setInt(3, 1);
                ps.setString(4, "测试商品");
                ps.setInt(5, 1);
                ps.setBigDecimal(6, new BigDecimal("9.90"));
                ps.setString(7, status);
                ps.setString(8, "PENDING".equals(status) ? null : String.format("2026-10-23 %02d:00:00", hour));
                ps.setString(9, "张三");
                ps.setString(10, "13800000000");
                ps.setString(11, "测试地址");
                ps.addBatch();

                statusCount.merge(shopId + "|" + status, 1, Integer::sum);
                if (!"PENDING".equals(status)) {
                    paidCount.merge(shopId, 1, Integer::sum);
                    paidAmount.merge(shopId, new BigDecimal("9.90"), BigDecimal::add);
                    paidHours.computeIfAbsent(shopId, k -> new HashSet<>()).add(hour);
                }
            }
            ps.executeBatch();
        }
        conn.commit();
        conn.setAutoCommit(true);
    }

    /** 组合索引契约：存在列完全等于期望（含顺序）的索引，仅 shop_id 单列不算达标 */
    private boolean hasIndexOn(String table, String... columns) throws Exception {
        List<String> expected = Arrays.asList(columns);
        List<String> indexNames = new ArrayList<>();
        try (Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("PRAGMA index_list(" + table + ")");
            while (rs.next()) {
                indexNames.add(rs.getString("name"));
            }
        }
        for (String name : indexNames) {
            List<String> cols = new ArrayList<>();
            try (Statement st = conn.createStatement()) {
                ResultSet info = st.executeQuery("PRAGMA index_info(" + name + ")");
                while (info.next()) {
                    cols.add(info.getString("name"));
                }
            }
            if (cols.equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private String explain(String sql) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("EXPLAIN QUERY PLAN " + sql);
            while (rs.next()) {
                sb.append(rs.getString("detail")).append(" | ");
            }
        }
        return sb.toString();
    }

    /** 计时执行（含完整遍历） */
    private long timed(String sql) throws Exception {
        long start = System.currentTimeMillis();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                // 遍历保证完整执行
            }
        }
        return System.currentTimeMillis() - start;
    }

    /**
     * 列：用例名、SQL 键、期望索引列（逗号分隔，含顺序）
     */
    static Stream<Arguments> indexCases() {
        return Stream.of(
                // ----1. 字段级校验：组合索引列齐全且 shop_id 在前（仅单列 shop_id 不达标）----
                arguments("状态计数命中shop_id+status组合索引", "COUNT", "shop_id,status"),
                arguments("小时聚合命中shop_id+pay_time组合索引", "HOUR", "shop_id,pay_time"),

                // ----2. 类型与格式：满量 1 万条计时 < 100ms（runner 统一断言）----
                arguments("今日支付订单数命中shop_id+pay_time组合索引", "PAID_COUNT", "shop_id,pay_time")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("indexCases")
    void 统计SQL_命中组合索引且万条计时达标(String caseName, String sqlKey,
                                          String expectedColumns) throws Exception {
        String sql = pickSql(sqlKey);

        // 1. 组合索引列契约（Fail 边界：只有 shop_id 单列索引时此断言失败）
        assertTrue(hasIndexOn("tb_order", expectedColumns.split(",")),
                caseName + "：缺少组合索引 tb_order(" + expectedColumns + ")");

        // 2. 查询计划走索引（不含 USING INDEX 即为全表扫描，不达标）
        String plan = explain(sql);
        assertTrue(plan.contains("USING INDEX") || plan.contains("USING COVERING INDEX"),
                caseName + "：查询计划未走索引，实际：" + plan);

        // 3. 满量数据计时 < 100ms
        long ms = timed(sql);
        assertTrue(ms < 100, caseName + "：耗时 " + ms + "ms 超过 100ms");

        // 4. 结果正确（索引不改变统计口径）
        assertResult(sqlKey, sql);
    }

    private String pickSql(String sqlKey) {
        switch (sqlKey) {
            case "COUNT":
                return COUNT_SQL;
            case "HOUR":
                return HOUR_SQL;
            default:
                return PAID_COUNT_SQL;
        }
    }

    private void assertResult(String sqlKey, String sql) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if ("COUNT".equals(sqlKey)) {
                rs.next();
                assertEquals(statusCount.get("1|PENDING").intValue(), rs.getInt(1), "PENDING 计数口径");
            } else if ("PAID_COUNT".equals(sqlKey)) {
                rs.next();
                assertEquals(paidCount.get(1).intValue(), rs.getInt(1), "今日支付订单数口径");
            } else {
                BigDecimal sum = BigDecimal.ZERO;
                int rows = 0;
                while (rs.next()) {
                    rows++;
                    sum = sum.add(rs.getBigDecimal("amount"));
                }
                assertEquals(paidHours.get(1).size(), rows, "小时聚合行数口径");
                assertEquals(paidAmount.get(1).setScale(2), sum.setScale(2, java.math.RoundingMode.HALF_UP),
                        "小时聚合金额口径");
            }
        }
    }

    // ----3. 重复创建：查询无创建语义 -> 重复执行结果一致（幂等）----

    @Test
    void 重复执行统计_结果一致且幂等() throws Exception {
        assertTrue(hasIndexOn("tb_order", "shop_id", "status"), "缺少组合索引 tb_order(shop_id, status)");
        try (Statement st = conn.createStatement()) {
            ResultSet first = st.executeQuery(COUNT_SQL);
            first.next();
            int firstValue = first.getInt(1);
            ResultSet second = st.executeQuery(COUNT_SQL);
            second.next();
            assertEquals(firstValue, second.getInt(1), "重复执行结果应一致");
        }
    }

    // ----4. 非法输入：跨店统计互不可见且各自走索引----

    @Test
    void 跨店统计_走组合索引且互不可见() throws Exception {
        assertTrue(hasIndexOn("tb_order", "shop_id", "status"), "缺少组合索引 tb_order(shop_id, status)");
        try (Statement st = conn.createStatement()) {
            int shop1 = queryInt(st, COUNT_SQL);
            int shop2 = queryInt(st, COUNT_SQL.replace("shop_id = 1", "shop_id = 2"));
            assertEquals(statusCount.get("1|PENDING").intValue(), shop1, "店铺 1 口径");
            assertEquals(statusCount.get("2|PENDING").intValue(), shop2, "店铺 2 口径");
            assertTrue(shop1 + shop2 <= statusCount.values().stream().mapToInt(Integer::intValue).sum(),
                    "两店计数之和不得超过全表总数");
            String plan = explain(COUNT_SQL.replace("shop_id = 1", "shop_id = 2"));
            assertTrue(plan.contains("USING INDEX") || plan.contains("USING COVERING INDEX"),
                    "店铺 2 查询计划未走索引，实际：" + plan);
        }
    }

    private int queryInt(Statement st, String sql) throws Exception {
        ResultSet rs = st.executeQuery(sql);
        rs.next();
        return rs.getInt(1);
    }
}
