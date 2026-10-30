package com.ysh.b2cmall.product.dao;

import com.ysh.b2cmall.product.dao.mapper.SkuMapper;
import com.ysh.b2cmall.product.dao.po.SkuPO;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * tb_sku 商品管理字段扩展的 DAO 层真实 SQLite 测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-product-list.md 功能上下文 1.1（DAO/PO 层 - 表结构与实体）：
 * - 1.1.1 product.sql 的 tb_sku 新增字段 market_price、sales、fake_sales、store_stock、
 *   product_type、delivery_methods、sort；type 扩展 3-卡券；先 DROP 旧表重建
 *   （Fail 边界：漏字段 → no such column；忘 DROP 旧表 → 新字段静默丢失）
 * - 1.1.2 SkuPO 同步新增属性，insert 后按 id 查询断言属性非 null 且值正确
 *   （Fail 边界：PO 漏属性导致 MyBatis 映射丢列）
 * - 1.1.3 默认值约束：sales=0、fake_sales=0、sort=0、store_stock=0
 *   （Fail 边界：默认值缺失 → 列表显示 null 与「暂无数据」混淆）
 *
 * 用例分类（表格驱动，适配表结构与实体校验）：
 * 1. 字段级校验：7 个新字段写入读回；SkuPO 新属性经 Mapper 映射读回
 * 2. 类型与格式：价格两位小数、type=3 卡券、枚举整型、配送方式单值/多值串
 * 3. 重复创建：建表脚本重复执行幂等重建（旧表被 DROP）；同内容商品重复插入为独立记录
 * 4. 非法输入：未传入新字段落默认 0 而非 null；必填列显式 NULL 被 NOT NULL 约束拒绝
 *
 * 建表 DDL 全部来自根目录 product.sql（被测物即建表脚本本身）。
 * 测试库为 @TempDir 下的临时 SQLite 库（绝对路径连接串）。
 */
class SkuSchemaTest {

    @TempDir
    File tempDir;

    private Connection conn;
    private SqlSession session;
    private SkuMapper skuMapper;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "sku-schema-test.db").getAbsolutePath();
        conn = DriverManager.getConnection(jdbcUrl);
        executeSchemaFile("product.sql");

        UnpooledDataSource dataSource = new UnpooledDataSource("org.sqlite.JDBC", jdbcUrl, null, null);
        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        try (InputStream in = Resources.getResourceAsStream("mapper/SkuMapper.xml")) {
            new XMLMapperBuilder(in, configuration, "mapper/SkuMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        session = sqlSessionFactory.openSession(true);
        skuMapper = session.getMapper(SkuMapper.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (session != null) {
            session.close();
        }
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

    /** 全字段插入（含 7 个新字段），targetColumn 覆盖为 sqlLiteral，其余列填固定合法值 */
    private void insertRowWith(String targetColumn, String sqlLiteral) throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("shop_id", "1");
        values.put("category_id", "1");
        values.put("name", "'全字段商品'");
        values.put("description", "'描述'");
        values.put("price", "'88.00'");
        values.put("stock", "50");
        values.put("stock_threshold", "10");
        values.put("type", "1");
        values.put("images", "'img1.png,img2.png'");
        values.put("spec", "'规格'");
        values.put("status", "1");
        values.put("market_price", "'99.50'");
        values.put("sales", "123");
        values.put("fake_sales", "45");
        values.put("store_stock", "8");
        values.put("product_type", "2");
        values.put("delivery_methods", "'CITY,PICKUP,EXPRESS'");
        values.put("sort", "7");
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
        executeSql("INSERT INTO tb_sku (" + cols + ") VALUES (" + vals + ")");
    }

    /** 读回最新一行的目标列 */
    private Object readValue(String column) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + column + " FROM tb_sku ORDER BY id DESC LIMIT 1")) {
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
                // ----1. 字段级校验：7 个新字段写入读回----
                arguments("市场价写入读回", "market_price", "'99.50'", new BigDecimal("99.50")),
                arguments("真实销量写入读回", "sales", "123", 123),
                arguments("注水销量写入读回", "fake_sales", "45", 45),
                arguments("门店库存写入读回", "store_stock", "8", 8),
                arguments("商品类型写入读回", "product_type", "2", 2),
                arguments("配送方式写入读回", "delivery_methods", "'CITY,PICKUP,EXPRESS'", "CITY,PICKUP,EXPRESS"),
                arguments("排序写入读回", "sort", "7", 7)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("newFieldRoundTripCases")
    void insert_新字段_写入读回一致(String caseName, String column, String sqlLiteral, Object expected) throws Exception {
        insertRowWith(column, sqlLiteral);

        assertValueEquals(expected, readValue(column), caseName + "：字段应能写入并原样读回");
    }

    // ============ 类型与格式（表格驱动） ============

    /**
     * 列：用例名、目标列、写入的 SQL 字面量、期望读回值
     */
    static Stream<Arguments> fieldFormatCases() {
        return Stream.of(
                // ----2. 类型与格式：价格两位小数、type=3 卡券、枚举整型、配送方式串----
                arguments("市场价两位小数12.34", "market_price", "12.34", new BigDecimal("12.34")),
                arguments("销售价两位小数88.88", "price", "88.88", new BigDecimal("88.88")),
                arguments("商品类别3卡券可存取", "type", "3", 3),
                arguments("商品类型1普通整型", "product_type", "1", 1),
                arguments("配送方式单值串", "delivery_methods", "'CITY'", "CITY")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fieldFormatCases")
    void insert_字段格式_按类型读回(String caseName, String column, String sqlLiteral, Object expected) throws Exception {
        insertRowWith(column, sqlLiteral);

        assertValueEquals(expected, readValue(column), caseName + "：格式化值应按类型正确读回");
    }

    // ============ 重复创建 ============

    @Test
    void executeSchema_重复执行_旧表被重建且新字段齐全() throws Exception {
        // 先伪造旧结构 tb_sku（缺新字段）并留一行旧数据
        executeSql("DROP TABLE IF EXISTS tb_sku");
        executeSql("CREATE TABLE tb_sku ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "shop_id INTEGER NOT NULL,"
                + "category_id INTEGER NOT NULL,"
                + "name VARCHAR(100) NOT NULL,"
                + "price DECIMAL(10,2) NOT NULL,"
                + "type TINYINT NOT NULL)");
        executeSql("INSERT INTO tb_sku (shop_id, category_id, name, price, type) VALUES (1,1,'旧表商品',1.00,1)");

        // 清掉脚本会重建的其他表，聚焦验证 tb_sku 的 DROP 重建语义
        executeSql("DROP TABLE IF EXISTS tb_category");
        executeSql("DROP TABLE IF EXISTS tb_sku_log");

        executeSchemaFile("product.sql");

        insertRowWith("market_price", "'66.00'");
        assertValueEquals(new BigDecimal("66.00"), readValue("market_price"),
                "重建后新字段应可写入读回（忘 DROP 旧表时新字段会被静默丢失）");
    }

    @Test
    void insert_同内容商品重复插入_生成独立记录() throws Exception {
        insertRowWith("sort", "7");
        insertRowWith("sort", "7");

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*), COUNT(DISTINCT id) FROM tb_sku")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1), "同内容商品应可重复创建为 2 条记录");
            assertEquals(2, rs.getInt(2), "重复创建应生成互不覆盖的独立 id");
        }
    }

    // ============ 1.1.3 默认值约束（表格驱动） ============

    /**
     * 列：用例名、默认值列名
     */
    static Stream<Arguments> defaultValueCases() {
        return Stream.of(
                // ----4. 非法输入：未显式传入的字段落默认 0 而非 null----
                arguments("未传真实销量落0", "sales"),
                arguments("未传注水销量落0", "fake_sales"),
                arguments("未传排序落0", "sort"),
                arguments("未传门店库存落0", "store_stock")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("defaultValueCases")
    void insert_未传新字段_落默认值零(String caseName, String column) throws Exception {
        executeSql("INSERT INTO tb_sku (shop_id, category_id, name, price, stock, stock_threshold, type, status)"
                + " VALUES (1,1,'缺省商品',88.00,50,10,1,1)");

        assertEquals(0, readValue(column), caseName + "：未传入时应落默认 0 而非 null");
    }

    // ============ 非法输入：必填列被约束拒绝（表格驱动） ============

    /**
     * 列：用例名、显式置 NULL 的必填列
     */
    static Stream<Arguments> notNullRejectCases() {
        return Stream.of(
                // ----4. 非法输入：必填列显式 NULL 被 NOT NULL 约束拒绝----
                arguments("名称为空被拒", "name"),
                arguments("所属店铺为空被拒", "shop_id"),
                arguments("类目为空被拒", "category_id"),
                arguments("销售价为空被拒", "price"),
                arguments("商品类别为空被拒", "type")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notNullRejectCases")
    void insert_必填列显式NULL_被约束拒绝(String caseName, String column) {
        SQLException ex = assertThrows(SQLException.class,
                () -> insertRowWith(column, "NULL"), caseName + "：必填列不应接受 NULL");

        assertTrue(ex.getMessage().contains("NOT NULL constraint failed: tb_sku." + column),
                caseName + "：应报 NOT NULL 约束错误，实际：" + ex.getMessage());
    }

    // ============ 1.1.2 SkuPO 新属性经 Mapper 映射读回（表格驱动） ============

    /**
     * 列：用例名、属性写入动作、属性读取函数、期望值
     */
    static Stream<Arguments> poAttributeCases() {
        return Stream.of(
                // ----1. 字段级校验：新属性经 Mapper 写入读回----
                arguments("市场价属性映射",
                        (Consumer<SkuPO>) po -> po.setMarketPrice(new BigDecimal("99.50")),
                        (Function<SkuPO, Object>) SkuPO::getMarketPrice, new BigDecimal("99.50")),
                arguments("真实销量属性映射",
                        (Consumer<SkuPO>) po -> po.setSales(123),
                        (Function<SkuPO, Object>) SkuPO::getSales, 123),
                arguments("注水销量属性映射",
                        (Consumer<SkuPO>) po -> po.setFakeSales(45),
                        (Function<SkuPO, Object>) SkuPO::getFakeSales, 45),
                arguments("门店库存属性映射",
                        (Consumer<SkuPO>) po -> po.setStoreStock(8),
                        (Function<SkuPO, Object>) SkuPO::getStoreStock, 8),
                arguments("商品类型属性映射",
                        (Consumer<SkuPO>) po -> po.setProductType(2),
                        (Function<SkuPO, Object>) SkuPO::getProductType, 2),
                arguments("配送方式属性映射",
                        (Consumer<SkuPO>) po -> po.setDeliveryMethods("CITY,PICKUP,EXPRESS"),
                        (Function<SkuPO, Object>) SkuPO::getDeliveryMethods, "CITY,PICKUP,EXPRESS"),

                // ----2. 类型与格式：排序数值型、市场价 BigDecimal 两位小数----
                arguments("排序属性映射",
                        (Consumer<SkuPO>) po -> po.setSort(7),
                        (Function<SkuPO, Object>) SkuPO::getSort, 7),
                arguments("市场价两位小数属性映射",
                        (Consumer<SkuPO>) po -> po.setMarketPrice(new BigDecimal("12.34")),
                        (Function<SkuPO, Object>) SkuPO::getMarketPrice, new BigDecimal("12.34"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("poAttributeCases")
    void insert_经Mapper_新属性读回(String caseName, Consumer<SkuPO> setter,
                                   Function<SkuPO, Object> getter, Object expected) {
        SkuPO po = new SkuPO();
        po.setShopId(1);
        po.setCategoryId(1);
        po.setName("PO映射商品");
        po.setPrice(new BigDecimal("88.00"));
        po.setType(1);
        po.setStatus(1);
        setter.accept(po);

        skuMapper.insert(po);

        SkuPO saved = skuMapper.selectById(po.getId());
        assertValueEquals(expected, getter.apply(saved), caseName + "：PO 属性应非 null 且值正确");
    }
}
