package com.ysh.b2cmall.product.dao.mapper;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库存预警计数满量计时验收（TDD 6.1.2）。
 * 对应 .tdd/task/shop-dashboard.md 功能上下文 6.1 任务项 6.1.2：
 * product-service 的库存预警计数纳入下游计时链路，单次 < 300ms 为聚合留余量
 * （Fail 边界：单下游就超 600ms 时聚合必然超标）。
 * 建表 DDL 来自根目录 product.sql（含 6.2 组合索引），满量 1000 sku。
 * 本测试为验收计时（被测物已实现），只断言耗时与计数口径，不做红绿循环。
 *
 * 测试库为 @TempDir 下的临时 SQLite 库（绝对路径连接串），MyBatis 绑定真实 SkuMapper.xml。
 */
class SkuStockTimingAcceptanceTest {

    private static final int ROW_COUNT = 1000;

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private SkuMapper skuMapper;
    /** 期望值跟踪：店铺 1 的预警 sku 数 */
    private int expectedAlertShop1;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "sku-timing.db").getAbsolutePath();
        dataSource = new UnpooledDataSource("org.sqlite.JDBC", jdbcUrl, null, null);

        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        try (InputStream in = Resources.getResourceAsStream("mapper/SkuMapper.xml")) {
            new XMLMapperBuilder(in, configuration, "mapper/SkuMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        session = sqlSessionFactory.openSession(true);
        skuMapper = session.getMapper(SkuMapper.class);

        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            executeSchemaFile(conn, "product.sql");
            insertSkus(conn);
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

    /** 满量 1000 sku（两店各 500，一半库存低于自身阈值预警、一半正常） */
    private void insertSkus(Connection conn) throws Exception {
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

                if (shopId == 1 && alert) {
                    expectedAlertShop1++;
                }
            }
            ps.executeBatch();
        }
        conn.commit();
        conn.setAutoCommit(true);
    }

    @Test
    void 库存预警计数_满量计时小于300ms() {
        long start = System.nanoTime();
        int alert = skuMapper.countStockAlertByShopId(1);
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertEquals(expectedAlertShop1, alert, "库存预警计数口径");
        System.out.println("[6.1.2] product 库存预警计数耗时 " + ms + "ms");
        assertTrue(ms < 300,
                "库存预警计数耗时 " + ms + "ms 超过 300ms（Fail 边界：单下游超 600ms 时聚合必然超标）");
    }
}
