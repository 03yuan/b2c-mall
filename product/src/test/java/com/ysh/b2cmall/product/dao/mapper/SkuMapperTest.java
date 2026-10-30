package com.ysh.b2cmall.product.dao.mapper;

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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * SkuMapper 库存预警统计 SQL 的真实 SQLite 测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-dashboard.md 功能上下文 2.2（product-service 库存预警统计）：
 * - 2.2.1 tb_sku 新增 stock_threshold 字段（商品级预警阈值）：插入带阈值的 sku → 字段可读回
 *   （Fail 边界：建表脚本漏字段后 SQL 报 no such column）
 * - 2.2.2 库存预警计数：count(*) where stock < stock_threshold（shopId 维度）
 *   （Fail 边界：条件写成 stock <= threshold 或用全局阈值）
 *
 * 用例分类（表格驱动，适配 DAO 计数/字段读回行为）：
 * 1. 字段级校验：严格 stock < 自身阈值；等值不预警
 * 2. 类型与格式：各 sku 按自身阈值比较（非全局阈值）；无数据计 0
 * 3. 重复创建：重复插入预警商品，计数累加
 * 4. 非法输入：阈值/库存为空不计入；跨店数据互不可见（shopId 隔离）
 *
 * 测试库为 @TempDir 下的临时 SQLite 库（绝对路径连接串）。
 * stock_threshold 为本功能新增字段，绿阶段需同步写入 product.sql（先删旧表重建）。
 */
class SkuMapperTest {

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private SkuMapper skuMapper;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "product-test.db").getAbsolutePath();
        dataSource = new UnpooledDataSource("org.sqlite.JDBC", jdbcUrl, null, null);

        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        try (InputStream in = Resources.getResourceAsStream("mapper/SkuMapper.xml")) {
            new XMLMapperBuilder(in, configuration, "mapper/SkuMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);

        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS tb_sku");
            st.executeUpdate("CREATE TABLE tb_sku ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "shop_id INTEGER NOT NULL,"
                    + "category_id INTEGER NOT NULL,"
                    + "name VARCHAR(100) NOT NULL,"
                    + "description TEXT,"
                    + "price DECIMAL(10,2) NOT NULL,"
                    + "market_price DECIMAL(10,2),"
                    + "stock INTEGER,"
                    + "stock_threshold INTEGER,"
                    + "sales INTEGER DEFAULT 0,"
                    + "fake_sales INTEGER DEFAULT 0,"
                    + "store_stock INTEGER DEFAULT 0,"
                    + "type TINYINT NOT NULL,"
                    + "product_type TINYINT,"

                    + "images VARCHAR(1000),"
                    + "spec VARCHAR(500),"
                    + "delivery_methods VARCHAR(100),"
                    + "sort INTEGER DEFAULT 0,"
                    + "status TINYINT DEFAULT 0,"
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
        }

        session = sqlSessionFactory.openSession(true);
        skuMapper = session.getMapper(SkuMapper.class);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    /** 形如 "库存:阈值"，null 表示 SQL NULL */
    private void insertSku(int shopId, String stockAndThreshold) throws Exception {
        String[] parts = stockAndThreshold.split(":");
        Integer stock = "null".equals(parts[0]) ? null : Integer.valueOf(parts[0]);
        Integer threshold = "null".equals(parts[1]) ? null : Integer.valueOf(parts[1]);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_sku (shop_id, category_id, name, price, stock, stock_threshold, type, status)"
                             + " VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setInt(1, shopId);
            ps.setInt(2, 1);
            ps.setString(3, "预警测试商品");
            ps.setBigDecimal(4, new BigDecimal("9.90"));
            if (stock == null) {
                ps.setNull(5, Types.INTEGER);
            } else {
                ps.setInt(5, stock);
            }
            if (threshold == null) {
                ps.setNull(6, Types.INTEGER);
            } else {
                ps.setInt(6, threshold);
            }
            ps.setInt(7, 1);
            ps.setInt(8, 1);
            ps.executeUpdate();
        }
    }

    // ============ 2.2.1 stock_threshold 字段：插入带阈值的 sku → 字段可读回 ============

    /**
     * 列：用例名、写入的阈值、期望读回的阈值
     */
    static Stream<Arguments> thresholdRoundTripCases() {
        return Stream.of(
                // ----1. 字段级校验：写入的阈值原样读回----
                arguments("阈值5读回5", 5),
                arguments("阈值100读回100", 100),

                // ----2. 类型与格式：数值型阈值，0 是合法阈值而非空----
                arguments("阈值0读回0", 0)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("thresholdRoundTripCases")
    void insert_带库存预警阈值_字段可读回(String caseName, Integer threshold) {
        SkuPO po = new SkuPO();
        po.setShopId(1);
        po.setCategoryId(1);
        po.setName("阈值读回测试商品");
        po.setPrice(new BigDecimal("9.90"));
        po.setStock(5);
        po.setType(1);
        po.setStatus(1);
        po.setStockThreshold(threshold);

        skuMapper.insert(po);

        SkuPO saved = skuMapper.selectById(po.getId());
        assertEquals(threshold, saved.getStockThreshold(), "stock_threshold 应能写入并原样读回");
    }

    // ============ 2.2.2 库存预警计数：stock < 自身阈值（表格驱动） ============

    /**
     * 列：用例名、店铺 1 插入的 sku 列表（库存:阈值，逗号分隔，null 表示空）、期望计数
     */
    static Stream<Arguments> countStockAlertCases() {
        return Stream.of(
                // ----1. 字段级校验：严格小于自身阈值才算预警----
                arguments("2个低于阈值1个高于计2", "5:10,3:5,20:10", 2),
                arguments("库存等于阈值不预警", "10:10", 0),

                // ----2. 类型与格式：各 sku 按自身阈值比较（非全局阈值）；无数据计 0----
                arguments("阈值各比各的而非全局阈值", "5:10,5:3", 1),
                arguments("无商品计0", "", 0),

                // ----3. 重复创建：重复插入预警商品计数累加----
                arguments("重复插入4个预警商品计4", "1:2,1:2,1:2,1:2", 4),

                // ----4. 非法输入：阈值/库存为空、库存充足均不计入（跨店隔离见单独用例）----
                arguments("阈值为空不计入", "5:null", 0),
                arguments("库存为空不计入", "null:10", 0),
                arguments("库存均不低于阈值计0", "20:10,15:10", 0)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("countStockAlertCases")
    void countStockAlertByShopId(String caseName, String skus, int expected) throws Exception {
        for (String sku : skus.split(",")) {
            if (!sku.isEmpty()) {
                insertSku(1, sku);
            }
        }

        assertEquals(expected, skuMapper.countStockAlertByShopId(1));
    }

    // ============ 2.2.2 shopId 隔离：不可跨店 ============

    @Test
    void countStockAlertByShopId_跨店隔离_只计自己的() throws Exception {
        insertSku(1, "5:10");
        insertSku(1, "3:5");
        insertSku(2, "1:2");

        assertEquals(2, skuMapper.countStockAlertByShopId(1), "店铺 1 应只统计到自己的 2 个预警商品");
        assertEquals(1, skuMapper.countStockAlertByShopId(2), "店铺 2 应只统计到自己的 1 个预警商品");
    }
}
