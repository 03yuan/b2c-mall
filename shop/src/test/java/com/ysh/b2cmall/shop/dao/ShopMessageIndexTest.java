package com.ysh.b2cmall.shop.dao;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * tb_shop_message 消息倒序分页查询索引的 DAO 层性能测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-dashboard.md 功能上下文 6.2：
 * - 6.2.2 tb_shop_message 的 shop_id+created_at 查询加对应索引
 *   （Fail 边界：消息表按时间倒序无索引时分页越翻越慢）
 * 建表与索引 DDL 全部来自根目录 shop.sql（被测物即建表脚本本身）：
 * 测试先执行该脚本建库，再灌满量 1 万条消息验证索引命中与计时。
 *
 * 用例分类（表格驱动，适配 DAO 索引保障）：
 * 1. 字段级校验：倒序分页命中的组合索引列齐全且 shop_id 在前（仅单列 shop_id 不达标）
 * 2. 类型与格式：满量 1 万条下首页/深翻页计时均 < 100ms（深页越翻越慢即 Fail）
 * 3. 重复创建：查询无创建语义 -> 重复执行结果一致（幂等，见独立测试）
 * 4. 非法输入：跨店消息互不可见且各自走索引（见独立测试）
 *
 * 测试库为 @TempDir 下的临时 SQLite 库（绝对路径连接串）。
 */
class ShopMessageIndexTest {

    private static final int ROW_COUNT = 10000;

    /** 与 ShopMessageMapper.xml selectPageByShopId 同构（参数字面化：店铺 1 首页 10 条） */
    private static final String PAGE_1_SQL =
            "SELECT * FROM tb_shop_message WHERE shop_id = 1 "
                    + "ORDER BY created_at DESC, id DESC LIMIT 10 OFFSET 0";

    /** 深翻页（店铺 1 共 5000 条，末页 offset 4990）：无索引时越翻越慢 */
    private static final String DEEP_PAGE_SQL =
            "SELECT * FROM tb_shop_message WHERE shop_id = 1 "
                    + "ORDER BY created_at DESC, id DESC LIMIT 10 OFFSET 4990";

    @TempDir
    File tempDir;

    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "message-index-test.db").getAbsolutePath();
        conn = DriverManager.getConnection(jdbcUrl);
        executeSchemaFile("shop.sql");
        insertMessages();
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

    /** 满量 1 万条消息（两店各 5000，created_at 严格递增可倒序） */
    private void insertMessages() throws Exception {
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tb_shop_message (shop_id, type, content, order_id, created_at) VALUES (?,?,?,?,?)")) {
            for (int i = 0; i < ROW_COUNT; i++) {
                int shopId = (i % 2) + 1;
                ps.setInt(1, shopId);
                ps.setString(2, "PAY_SUCCESS");
                ps.setString(3, "测试消息 " + i);
                ps.setInt(4, i);
                ps.setString(5, String.format("2026-10-%02d %02d:%02d:%02d",
                        1 + (i / 86400), (i / 3600) % 24, (i / 60) % 60, i % 60));
                ps.addBatch();
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

    /** 计时执行并返回结果行数 */
    private long timedCount(String sql, int expectedRows) throws Exception {
        long start = System.currentTimeMillis();
        int rows = 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rows++;
            }
        }
        long ms = System.currentTimeMillis() - start;
        assertEquals(expectedRows, rows, "分页返回行数口径");
        return ms;
    }

    /**
     * 列：用例名、SQL（首页/深翻页）
     */
    static Stream<Arguments> indexCases() {
        return Stream.of(
                // ----1. 字段级校验：组合索引列齐全且 shop_id 在前（仅单列 shop_id 不达标）----
                arguments("首页倒序分页命中shop_id+created_at组合索引", PAGE_1_SQL),

                // ----2. 类型与格式：满量计时 < 100ms，深翻页不得越翻越慢（runner 统一断言）----
                arguments("深翻页计时达标不越翻越慢", DEEP_PAGE_SQL)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("indexCases")
    void 倒序分页查询_命中组合索引且万条计时达标(String caseName, String sql) throws Exception {
        // 1. 组合索引列契约（Fail 边界：只有 shop_id 单列索引时此断言失败）
        assertTrue(hasIndexOn("tb_shop_message", "shop_id", "created_at"),
                caseName + "：缺少组合索引 tb_shop_message(shop_id, created_at)");

        // 2. 查询计划走索引（不含 USING INDEX 即为全表扫描，不达标）
        String plan = explain(sql);
        assertTrue(plan.contains("USING INDEX") || plan.contains("USING COVERING INDEX"),
                caseName + "：查询计划未走索引，实际：" + plan);

        // 3. 满量数据计时 < 100ms
        long ms = timedCount(sql, 10);
        assertTrue(ms < 100, caseName + "：耗时 " + ms + "ms 超过 100ms");
    }

    // ----3. 重复创建：查询无创建语义 -> 重复执行结果一致（幂等）----

    @Test
    void 重复执行倒序分页_结果一致且幂等() throws Exception {
        assertTrue(hasIndexOn("tb_shop_message", "shop_id", "created_at"),
                "缺少组合索引 tb_shop_message(shop_id, created_at)");
        List<Integer> first = queryOrderIds(PAGE_1_SQL);
        List<Integer> second = queryOrderIds(PAGE_1_SQL);
        assertEquals(first, second, "重复执行结果应一致");
    }

    // ----4. 非法输入：跨店消息互不可见且各自走索引----

    @Test
    void 跨店消息分页_走组合索引且互不可见() throws Exception {
        assertTrue(hasIndexOn("tb_shop_message", "shop_id", "created_at"),
                "缺少组合索引 tb_shop_message(shop_id, created_at)");
        List<Integer> shop1Ids = queryOrderIds(PAGE_1_SQL);
        List<Integer> shop2Ids = queryOrderIds(PAGE_1_SQL.replace("shop_id = 1", "shop_id = 2"));
        assertEquals(10, shop1Ids.size(), "店铺 1 分页行数");
        assertEquals(10, shop2Ids.size(), "店铺 2 分页行数");
        for (int i = 1; i < shop1Ids.size(); i++) {
            assertTrue(shop1Ids.get(i - 1) > shop1Ids.get(i), "店铺 1 应按时间倒序（order_id 递减）");
            assertTrue(shop2Ids.get(i - 1) > shop2Ids.get(i), "店铺 2 应按时间倒序（order_id 递减）");
        }
        for (int id : shop1Ids) {
            assertTrue(id % 2 == 0, "店铺 1 不应看到店铺 2 的消息（奇数 order_id）");
        }
        String plan = explain(PAGE_1_SQL.replace("shop_id = 1", "shop_id = 2"));
        assertTrue(plan.contains("USING INDEX") || plan.contains("USING COVERING INDEX"),
                "店铺 2 查询计划未走索引，实际：" + plan);
    }

    private List<Integer> queryOrderIds(String sql) throws Exception {
        List<Integer> ids = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                ids.add(rs.getInt("order_id"));
            }
        }
        return ids;
    }
}
