package com.ysh.b2cmall.product.dao;

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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * tb_sku 库存预警查询索引的 DAO 层性能测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-dashboard.md 功能上下文 6.2：
 * - 6.2.2 tb_sku 的 stock 预警查询加对应索引（shop_id+stock 组合）
 *   （Fail 边界：满量数据下无索引全表扫描，统计口径越大越慢）
 * 建表与索引 DDL 全部来自根目录 product.sql（被测物即建表脚本本身）：
 * 测试先执行该脚本建库，再灌满量 1 万条 sku 验证索引命中与计时。
 *
 * 用例分类（表格驱动，适配 DAO 索引保障）：
 * 1. 字段级校验：库存预警查询命中的组合索引列齐全且 shop_id 在前（仅单列 shop_id 不达标）
 * 2. 类型与格式：满量数据下库存预警计数计时 < 100ms
 * 3. 重复创建：查询无创建语义 -> 重复执行结果一致（幂等，见独立测试）
 * 4. 非法输入：跨店预警计数互不可见且各自走索引（见独立测试）
 *
 * 测试库为 @TempDir 下的临时 SQLite 库（绝对路径连接串）。
 */
class SkuStockIndexTest {

    private static final int ROW_COUNT = 10000;

    /** 与 SkuMapper.xml countStockAlertByShopId 同构（参数字面化） */
    private static final String ALERT_SQL =
            "SELECT COUNT(*) FROM tb_sku WHERE shop_id = 1 AND stock < stock_threshold";

    @TempDir
    File tempDir;

    private Connection conn;
    /** 期望值跟踪：shopId -> 库存预警 sku 数 */
    private final Map<Integer, Integer> alertCount = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "sku-index-test.db").getAbsolutePath();
        conn = DriverManager.getConnection(jdbcUrl);
        executeSchemaFile("product.sql");
        insertSkus();
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

    /** 满量 1 万条 sku（两店各 5000，奇数 id 预警/偶数 id 正常），并同步跟踪期望值 */
    private void insertSkus() throws Exception {
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tb_sku (shop_id, category_id, name, price, stock, stock_threshold, type)"
                        + " VALUES (?,?,?,?,?,?,?)")) {
            for (int i = 0; i < ROW_COUNT; i++) {
                int shopId = (i % 2) + 1;
                boolean alert = (i / 2) % 2 == 0;
                ps.setInt(1, shopId);
                ps.setInt(2, 1);
                ps.setString(3, "测试商品 " + i);
                ps.setBigDecimal(4, new BigDecimal("9.90"));
                ps.setInt(5, alert ? 1 : 100);
                ps.setInt(6, 10);
                ps.setInt(7, 1);
                ps.addBatch();

                if (alert) {
                    alertCount.merge(shopId, 1, Integer::sum);
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
     * 列：用例名、期望索引列（逗号分隔，含顺序）
     */
    static Stream<Arguments> indexCases() {
        return Stream.of(
                // ----1. 字段级校验：组合索引列齐全且 shop_id 在前（仅单列 shop_id 不达标）----
                arguments("库存预警计数命中shop_id+stock组合索引", "shop_id,stock"),

                // ----2. 类型与格式：满量数据计时 < 100ms（runner 统一断言）----
                arguments("库存预警计数万条计时达标", "shop_id,stock")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("indexCases")
    void 库存预警计数_命中组合索引且万条计时达标(String caseName, String expectedColumns) throws Exception {
        // 1. 组合索引列契约（Fail 边界：只有 shop_id 单列索引时此断言失败）
        assertTrue(hasIndexOn("tb_sku", expectedColumns.split(",")),
                caseName + "：缺少组合索引 tb_sku(" + expectedColumns + ")");

        // 2. 查询计划走索引（不含 USING INDEX 即为全表扫描，不达标）
        String plan = explain(ALERT_SQL);
        assertTrue(plan.contains("USING INDEX") || plan.contains("USING COVERING INDEX"),
                caseName + "：查询计划未走索引，实际：" + plan);

        // 3. 满量数据计时 < 100ms
        long ms = timed(ALERT_SQL);
        assertTrue(ms < 100, caseName + "：耗时 " + ms + "ms 超过 100ms");

        // 4. 结果正确（索引不改变预警口径）
        assertAlertCount(1);
    }

    private void assertAlertCount(int shopId) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(ALERT_SQL.replace("shop_id = 1", "shop_id = " + shopId))) {
            rs.next();
            assertEquals(alertCount.get(shopId).intValue(), rs.getInt(1),
                    "店铺 " + shopId + " 库存预警计数口径");
        }
    }

    // ----3. 重复创建：查询无创建语义 -> 重复执行结果一致（幂等）----

    @Test
    void 重复执行预警计数_结果一致且幂等() throws Exception {
        assertTrue(hasIndexOn("tb_sku", "shop_id", "stock"), "缺少组合索引 tb_sku(shop_id, stock)");
        try (Statement st = conn.createStatement()) {
            ResultSet first = st.executeQuery(ALERT_SQL);
            first.next();
            int firstValue = first.getInt(1);
            ResultSet second = st.executeQuery(ALERT_SQL);
            second.next();
            assertEquals(firstValue, second.getInt(1), "重复执行结果应一致");
        }
    }

    // ----4. 非法输入：跨店预警计数互不可见且各自走索引----

    @Test
    void 跨店预警计数_走组合索引且互不可见() throws Exception {
        assertTrue(hasIndexOn("tb_sku", "shop_id", "stock"), "缺少组合索引 tb_sku(shop_id, stock)");
        try (Statement st = conn.createStatement()) {
            int shop1 = queryInt(st, ALERT_SQL);
            int shop2 = queryInt(st, ALERT_SQL.replace("shop_id = 1", "shop_id = 2"));
            assertEquals(alertCount.get(1).intValue(), shop1, "店铺 1 口径");
            assertEquals(alertCount.get(2).intValue(), shop2, "店铺 2 口径");
            String plan = explain(ALERT_SQL.replace("shop_id = 1", "shop_id = 2"));
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
