package com.ysh.order.dao;

import com.ysh.order.dao.po.OrderPO;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * tb_order 订单管理字段扩展的 DAO 层真实 SQLite 测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 1.1（DAO/PO 层 - 表结构与实体）：
 * - 1.1.1 order.sql 的 tb_order 新增 order_type、order_category、logistics_type、logistics_no、
 *   store_name、shop_name、sku_image、unit_price、is_gift、buyer_deleted；先 DROP 旧表重建
 *   （Fail 边界：漏字段 → no such column；忘 DROP 旧表 → 新字段静默丢失）
 * - 1.1.2 新增组合索引 idx_order_shop_create_time (shop_id, create_time)（三层断言）
 *   （Fail 边界：列顺序写反 → 按 shop_id 等值 + 时间范围时索引失效）
 * - 1.1.3 OrderPO 同步新增对应属性（反射断言字段存在且类型正确）
 *   （Fail 边界：PO 漏属性导致 MyBatis 映射丢列）
 * - 1.1.4 默认值：order_type='NORMAL'、order_category='NORMAL'、is_gift=0、buyer_deleted=0
 *   （Fail 边界：默认值缺失 → 列表/筛选项 null 与「暂无数据」混淆）
 *
 * 用例分类（表格驱动，适配表结构与实体校验）：
 * 1. 字段级校验：10 个新字段写入读回一致
 * 2. 类型与格式：金额两位小数、枚举字符串、赠品整型、可空列保持 null
 * 3. 重复创建：建表脚本重复执行幂等重建（旧表被 DROP，新字段可用）
 * 4. 非法输入：未传新字段落默认值而非 null；未传可空列保持 null 不伪造
 *
 * 建表 DDL 全部来自根目录 order.sql（被测物即建表脚本本身）。
 * 测试库为 @TempDir 下的临时 SQLite 库（绝对路径连接串）。
 * 红阶段：order.sql 与 OrderPO 尚未扩展，以下用例预期全部失败。
 */
class OrderSchemaTest {

    @TempDir
    File tempDir;

    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "order-schema-test.db").getAbsolutePath();
        conn = DriverManager.getConnection(jdbcUrl);
        executeSchemaFile("order.sql");
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
            executeSql(stmt);
        }
    }

    private void executeSql(String sql) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    /** 全字段插入（含 10 个新字段），targetColumn 覆盖为 sqlLiteral，其余列填固定合法值 */
    private void insertRowWith(String targetColumn, String sqlLiteral) throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("order_no", "'NO-20261026000001'");
        values.put("shop_id", "1");
        values.put("sku_id", "100");
        values.put("sku_name", "'全字段商品'");
        values.put("quantity", "2");
        values.put("total_price", "'39.80'");
        values.put("status", "'PAID'");
        values.put("pay_type", "'WECHAT'");
        values.put("receiver_name", "'张三'");
        values.put("receiver_phone", "'13800001234'");
        values.put("receiver_address", "'测试地址 1 号'");
        values.put("order_type", "'NORMAL'");
        values.put("order_category", "'CARD'");
        values.put("logistics_type", "'EXPRESS'");
        values.put("logistics_no", "'SF1234567890'");
        values.put("store_name", "'一号门店'");
        values.put("shop_name", "'测试店铺'");
        values.put("sku_image", "'https://img.example.com/a.png'");
        values.put("unit_price", "'19.90'");
        values.put("is_gift", "1");
        values.put("buyer_deleted", "1");
        values.put(targetColumn, sqlLiteral);

        StringBuilder cols = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (cols.length() > 0) {
                cols.append(", ");
                vals.append(", ");
            }
            cols.append(e.getKey());
            vals.append(e.getValue());
        }
        executeSql("INSERT INTO tb_order (" + cols + ") VALUES (" + vals + ")");
    }

    /** 读回最新一行的目标列 */
    private Object readValue(String column) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + column + " FROM tb_order ORDER BY id DESC LIMIT 1")) {
            assertTrue(rs.next(), "应存在一行数据");
            return rs.getObject(column);
        }
    }

    private void assertValueEquals(Object expected, Object actual, String message) {
        if (expected instanceof BigDecimal) {
            assertNotNull(actual, message + "（应读回数值而非 null）");
            assertEquals(0, ((BigDecimal) expected).compareTo(new BigDecimal(actual.toString())), message);
        } else {
            assertEquals(expected, actual, message);
        }
    }

    // ============ 1.1.1 新字段写入读回（表格驱动） ============

    /**
     * 列：用例名、目标列、写入的 SQL 字面量、期望读回值
     */
    static Stream<Arguments> newFieldRoundTripCases() {
        return Stream.of(
                // ----1. 字段级校验：10 个新字段写入读回----
                arguments("订单类型写入读回", "order_type", "'NORMAL'", "NORMAL"),
                arguments("订单类别写入读回", "order_category", "'CARD'", "CARD"),
                arguments("物流类型写入读回", "logistics_type", "'EXPRESS'", "EXPRESS"),
                arguments("物流单号写入读回", "logistics_no", "'SF1234567890'", "SF1234567890"),
                arguments("门店名称写入读回", "store_name", "'一号门店'", "一号门店"),
                arguments("店铺名称写入读回", "shop_name", "'测试店铺'", "测试店铺"),
                arguments("商品图片写入读回", "sku_image", "'https://img.example.com/a.png'",
                        "https://img.example.com/a.png"),
                arguments("成交单价写入读回", "unit_price", "'19.90'", new BigDecimal("19.90")),
                arguments("赠品标记写入读回", "is_gift", "1", 1),
                arguments("买家删除标记写入读回", "buyer_deleted", "1", 1)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("newFieldRoundTripCases")
    void insert_新字段_写入读回一致(String caseName, String column, String sqlLiteral, Object expected)
            throws Exception {
        insertRowWith(column, sqlLiteral);

        assertValueEquals(expected, readValue(column), caseName + "：字段应能写入并原样读回");
    }

    // ============ 类型与格式（表格驱动） ============

    /**
     * 列：用例名、目标列、写入的 SQL 字面量、期望读回值
     */
    static Stream<Arguments> fieldFormatCases() {
        return Stream.of(
                // ----2. 类型与格式：金额两位小数、枚举字符串、整型标记----
                arguments("成交单价两位小数12.34", "unit_price", "12.34", new BigDecimal("12.34")),
                arguments("成交单价最小金额0.01", "unit_price", "0.01", new BigDecimal("0.01")),
                arguments("订单类型秒杀取值", "order_type", "'SECKILL'", "SECKILL"),
                arguments("订单类别普通取值", "order_category", "'NORMAL'", "NORMAL"),
                arguments("物流类型同城配送取值", "logistics_type", "'CITY'", "CITY"),
                arguments("赠品标记0非赠品", "is_gift", "0", 0),
                arguments("买家删除标记0未删除", "buyer_deleted", "0", 0)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fieldFormatCases")
    void insert_字段格式_按类型读回(String caseName, String column, String sqlLiteral, Object expected)
            throws Exception {
        insertRowWith(column, sqlLiteral);

        assertValueEquals(expected, readValue(column), caseName + "：格式化值应按类型正确读回");
    }

    // ============ 3. 重复创建：建表脚本重复执行幂等重建 ============

    @Test
    void executeSchema_重复执行_旧表被重建且新字段齐全() throws Exception {
        // 先伪装旧结构 tb_order（缺新字段）并留一行旧数据
        executeSql("DROP TABLE IF EXISTS tb_order");
        executeSql("CREATE TABLE tb_order ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "order_no VARCHAR(64) NOT NULL UNIQUE,"
                + "shop_id INTEGER NOT NULL,"
                + "sku_id INTEGER NOT NULL,"
                + "sku_name VARCHAR(200) NOT NULL,"
                + "quantity INTEGER NOT NULL,"
                + "total_price DECIMAL(10,2) NOT NULL,"
                + "status VARCHAR(20) NOT NULL DEFAULT 'PENDING',"
                + "receiver_name VARCHAR(50) NOT NULL,"
                + "receiver_phone VARCHAR(20) NOT NULL,"
                + "receiver_address VARCHAR(500) NOT NULL)");
        executeSql("INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                + " status, receiver_name, receiver_phone, receiver_address)"
                + " VALUES ('OLD-1', 1, 1, '旧表商品', 1, 1.00, 'PENDING', '张三', '13800000000', '旧地址')");

        executeSchemaFile("order.sql");

        insertRowWith("logistics_no", "'YT999'");
        assertValueEquals("YT999", readValue("logistics_no"),
                "重建后新字段应可写入读回（忘 DROP 旧表时新字段会被静默丢失）");
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM tb_order WHERE order_no = 'OLD-1'")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "重建应先 DROP 旧表，旧数据不得残留");
        }
    }

    // ============ 1.1.4 默认值约束（表格驱动） ============

    /**
     * 列：用例名、默认值列名、期望默认值
     */
    static Stream<Arguments> defaultValueCases() {
        return Stream.of(
                // ----4. 非法输入：未显式传入的字段落默认值而非 null----
                arguments("未传订单类型落NORMAL", "order_type", "NORMAL"),
                arguments("未传订单类别落NORMAL", "order_category", "NORMAL"),
                arguments("未传赠品标记落0", "is_gift", 0),
                arguments("未传买家删除标记落0", "buyer_deleted", 0)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("defaultValueCases")
    void insert_未传新字段_落默认值(String caseName, String column, Object expected) throws Exception {
        executeSql("INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                + " status, receiver_name, receiver_phone, receiver_address)"
                + " VALUES ('NO-DEFAULT', 1, 1, '缺省商品', 1, 9.90, 'PENDING', '李四', '13900000000', '缺省地址')");

        assertValueEquals(expected, readValue(column), caseName + "：未传入时应落默认值而非 null");
    }

    // ============ 非法输入：可空列未传时保持 null（不伪造 0/空串） ============

    /**
     * 列：用例名、可空新列名
     */
    static Stream<Arguments> nullableColumnCases() {
        return Stream.of(
                // ----4. 非法输入：可空列未传保持 null，不伪造数值----
                arguments("未传成交单价保持null", "unit_price"),
                arguments("未传物流单号保持null", "logistics_no"),
                arguments("未传物流类型保持null", "logistics_type"),
                arguments("未传门店名称保持null", "store_name"),
                arguments("未传店铺名称保持null", "shop_name"),
                arguments("未传商品图片保持null", "sku_image")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nullableColumnCases")
    void insert_未传可空新列_保持null(String caseName, String column) throws Exception {
        executeSql("INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                + " status, receiver_name, receiver_phone, receiver_address)"
                + " VALUES ('NO-NULLABLE', 1, 1, '可空列商品', 1, 9.90, 'PENDING', '王五', '13700000000', '可空地址')");

        assertNull(readValue(column), caseName + "：未传入时应保持 null，不得伪造数值");
    }

    // ============ 1.1.2 组合索引 idx_order_shop_create_time（三层断言） ============

    private static final String RANGE_SQL =
            "SELECT id FROM tb_order WHERE shop_id = 1 "
                    + "AND create_time >= '2026-10-01 00:00:00' AND create_time <= '2026-10-31 23:59:59'";

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
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("EXPLAIN QUERY PLAN " + sql)) {
            while (rs.next()) {
                sb.append(rs.getString("detail")).append(" | ");
            }
        }
        return sb.toString();
    }

    @Test
    void 下单时间区间查询_命中shop_id加create_time组合索引() throws Exception {
        // 1. 索引列契约：必须存在 (shop_id, create_time) 且顺序正确
        assertTrue(hasIndexOn("tb_order", "shop_id", "create_time"),
                "缺少组合索引 idx_order_shop_create_time (shop_id, create_time)");

        // 2. 满量数据下查询计划走索引
        insertBulkOrders(5000);
        String plan = explain(RANGE_SQL);
        assertTrue(plan.contains("USING INDEX") || plan.contains("USING COVERING INDEX"),
                "下单时间区间查询未走索引，实际：" + plan);

        // 3. 满量数据计时达标
        long start = System.currentTimeMillis();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(RANGE_SQL)) {
            while (rs.next()) {
                // 遍历保证完整执行
            }
        }
        long ms = System.currentTimeMillis() - start;
        assertTrue(ms < 100, "下单时间区间查询耗时 " + ms + "ms 超过 100ms");
    }

    /** 满量订单数据（全部落在查询区间内，确保命中行数非零） */
    private void insertBulkOrders(int rowCount) throws Exception {
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                        + " status, create_time, receiver_name, receiver_phone, receiver_address)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            for (int i = 0; i < rowCount; i++) {
                ps.setString(1, "BULK-" + i);
                ps.setInt(2, (i % 2) + 1);
                ps.setInt(3, 1);
                ps.setString(4, "批量商品");
                ps.setInt(5, 1);
                ps.setBigDecimal(6, new BigDecimal("9.90"));
                ps.setString(7, "PAID");
                ps.setString(8, String.format("2026-10-%02d 10:00:00", (i % 28) + 1));
                ps.setString(9, "批量收件人");
                ps.setString(10, "13800000000");
                ps.setString(11, "批量地址");
                ps.addBatch();
            }
            ps.executeBatch();
        }
        conn.commit();
        conn.setAutoCommit(true);
    }

    // ============ 1.1.3 OrderPO 新属性（反射断言，红阶段不依赖 getter 编译） ============

    /**
     * 列：用例名、属性名、期望 Java 类型
     */
    static Stream<Arguments> poFieldCases() {
        return Stream.of(
                // ----1. 字段级校验：OrderPO 新增 10 个属性----
                arguments("订单类型属性", "orderType", "String"),
                arguments("订单类别属性", "orderCategory", "String"),
                arguments("物流类型属性", "logisticsType", "String"),
                arguments("物流单号属性", "logisticsNo", "String"),
                arguments("门店名称属性", "storeName", "String"),
                arguments("店铺名称属性", "shopName", "String"),
                arguments("商品图片属性", "skuImage", "String"),

                // ----2. 类型与格式：金额为 BigDecimal、标记为 Integer----
                arguments("成交单价属性", "unitPrice", "BigDecimal"),
                arguments("赠品标记属性", "isGift", "Integer"),
                arguments("买家删除标记属性", "buyerDeleted", "Integer")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("poFieldCases")
    void OrderPO_应声明新属性且类型正确(String caseName, String fieldName, String expectedType) throws Exception {
        java.lang.reflect.Field field = OrderPO.class.getDeclaredField(fieldName);

        assertEquals(expectedType, field.getType().getSimpleName(),
                caseName + "：OrderPO." + fieldName + " 类型应为 " + expectedType);
    }
}
