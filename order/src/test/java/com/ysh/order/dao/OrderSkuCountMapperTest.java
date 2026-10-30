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
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 商品订单计数 SQL 的真实 SQLite 表格驱动测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-product-list.md 功能上下文 7.3.1：
 * order-service 新增订单存在性统计能力：按 skuId 统计订单条数（shopId 隔离，
 * 任意状态的订单都算「已产生订单」）。
 * Fail 边界：只统计已支付订单 -> 有未支付单的商品被误删。
 *
 * 用例分类（表格驱动，适配 DAO 计数行为）：
 * 1. 字段级校验：同一 skuId 不同状态订单全部计入（PENDING 未支付也算）
 * 2. 类型与格式：无订单计 0（int 非 null）
 * 3. 重复创建：重复插入订单计数累加
 * 4. 非法输入：跨店互不可见（他店同 skuId/他店订单不计入）
 *
 * 固定数据：店 1 的 skuId=1 有 PENDING+PAID 两单、skuId=2 无订单；店 2 的 skuId=1 有一单。
 */
class OrderSkuCountMapperTest {

    @TempDir
    File tempDir;

    private SqlSession session;
    private OrderMapper orderMapper;
    private int orderNoSeq = 0;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "order-sku-count-test.db").getAbsolutePath();
        UnpooledDataSource dataSource = new UnpooledDataSource("org.sqlite.JDBC", jdbcUrl, null, null);

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
        }

        insertOrder(dataSource, 1, 1, "PENDING");  // 未支付也必须计入（Fail 边界）
        insertOrder(dataSource, 1, 1, "PAID");
        insertOrder(dataSource, 2, 1, "PAID");     // 他店同 skuId

        session = sqlSessionFactory.openSession(true);
        orderMapper = session.getMapper(OrderMapper.class);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private void insertOrder(UnpooledDataSource dataSource, int shopId, int skuId, String status) throws Exception {
        orderNoSeq++;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                             + " status, receiver_name, receiver_phone, receiver_address)"
                             + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, "NO-" + orderNoSeq);
            ps.setInt(2, shopId);
            ps.setInt(3, skuId);
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

    // ============ 表格用例：列 = 用例名、skuId、shopId、期望计数 ============

    static Stream<Arguments> countCases() {
        return Stream.of(
                arguments("本店该商品有2单（PENDING+PAID，任意状态都算）", 1, 1, 2),
                arguments("本店该商品无订单计0", 2, 1, 0),
                arguments("他店同skuId订单不计入（shopId隔离）", 1, 2, 1),
                arguments("他店视角只算自己的订单", 999, 1, 0)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("countCases")
    void 商品订单计数(String caseName, Integer skuId, Integer shopId, int expected) {
        assertEquals(expected, orderMapper.countBySkuId(skuId, shopId), caseName);
    }

    // ============ 3. 重复创建：重复插入订单计数累加 ============

    @Test
    void 重复插入订单_计数累加() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "order-sku-count-test.db").getAbsolutePath();
        UnpooledDataSource dataSource = new UnpooledDataSource("org.sqlite.JDBC", jdbcUrl, null, null);
        insertOrder(dataSource, 1, 2, "PENDING");
        insertOrder(dataSource, 1, 2, "PENDING");

        assertEquals(2, orderMapper.countBySkuId(2, 1), "重复插入两单后计数应为 2");
    }
}
