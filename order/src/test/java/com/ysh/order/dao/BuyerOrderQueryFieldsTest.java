package com.ysh.order.dao;

import com.ysh.order.dao.po.BuyerOrderQueryPO;
import com.ysh.order.dao.po.OrderPO;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 买家分页查询读出 buyer_fav / shop_phone 两列的真实 SQLite 测试（TDD 红阶段）。
 * 对应 .tdd/task/mall-order-manage.md 功能上下文 4.1（DAO 层 - 买家分页查询，新增两字段）：
 * - 4.1.1 selectBuyerPage 在既有筛选/排序/分页语义之上，补齐读出 buyer_fav、shop_phone 两列
 *   （供 4.2.2 卡片 shopPhone / 4.2.3 卡片 buyerFav 组装消费）。
 *   Fail 边界：selectBuyerPage 的 SELECT 列未含 buyer_fav/shop_phone -> 读回恒 null，
 *   导致卡片电话图标点击无号、收藏按钮态无法刷新。
 *
 * 分页/筛选/排序/隔离等既有语义已由 BuyerOrderMapperTest 覆盖，本测试只聚焦「新增两列的读取」。
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：buyer_fav=1 / shop_phone 有值 -> selectBuyerPage 读回一致
 * 2. 类型与格式：buyer_fav 默认 0 / shop_phone 为 null -> 读回 0 / null
 * 3. 重复创建：重复查询两列值一致
 * 4. 非法输入：他人/已删订单不出现（回归）；两列读取不影响 countBuyerByFilter 总数
 */
class BuyerOrderQueryFieldsTest {

    private static final String BUYER_A = "buyer_a";
    private static final String BUYER_B = "buyer_b";

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private OrderMapper orderMapper;

    private int aFavPhone;    // 买家 A：buyer_fav=1、shop_phone 有值
    private int aNoFavNoPhone; // 买家 A：buyer_fav=0（默认）、shop_phone=null

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "buyer-order-query-fields.db").getAbsolutePath();
        dataSource = new UnpooledDataSource("org.sqlite.JDBC", jdbcUrl, null, null);

        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
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
                    + "pay_time DATETIME,"
                    + "receiver_name VARCHAR(50) NOT NULL,"
                    + "receiver_phone VARCHAR(20) NOT NULL,"
                    + "receiver_address VARCHAR(500) NOT NULL,"
                    + "buyer_username VARCHAR(64),"
                    + "buyer_deleted INTEGER DEFAULT 0,"
                    + "buyer_fav INTEGER DEFAULT 0,"
                    + "shop_phone VARCHAR(20),"
                    + "order_type VARCHAR(20) DEFAULT 'NORMAL',"
                    + "order_category VARCHAR(20) DEFAULT 'NORMAL',"
                    + "logistics_type VARCHAR(20),"
                    + "logistics_no VARCHAR(64),"
                    + "store_name VARCHAR(100),"
                    + "shop_name VARCHAR(100),"
                    + "sku_image VARCHAR(500),"
                    + "unit_price DECIMAL(10,2),"
                    + "is_gift INTEGER DEFAULT 0,"
                    + "create_time DATETIME DEFAULT (datetime('now','localtime')),"
                    + "update_time DATETIME DEFAULT (datetime('now','localtime')))");
        }

        session = sqlSessionFactory.openSession(true);
        orderMapper = session.getMapper(OrderMapper.class);

        aFavPhone = insertOrder(BUYER_A, "PAID", 1, "13800000001");
        aNoFavNoPhone = insertOrder(BUYER_A, "SHIPPED", 0, null);
        insertOrder(BUYER_B, "PAID", 1, "13900000002"); // 他人陷阱
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private int insertOrder(String buyerUsername, String status, int buyerFav, String shopPhone) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                             + " status, receiver_name, receiver_phone, receiver_address,"
                             + " buyer_username, buyer_deleted, buyer_fav, shop_phone)"
                             + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "NO-" + System.nanoTime());
            ps.setInt(2, 1);
            ps.setInt(3, 100);
            ps.setString(4, "测试商品");
            ps.setInt(5, 2);
            ps.setBigDecimal(6, new java.math.BigDecimal("39.80"));
            ps.setString(7, status);
            ps.setString(8, "张三");
            ps.setString(9, "13800001111");
            ps.setString(10, "上海市浦东新区测试路 1 号");
            ps.setString(11, buyerUsername);
            ps.setInt(12, 0);
            ps.setInt(13, buyerFav);
            ps.setString(14, shopPhone);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private BuyerOrderQueryPO filterA() {
        BuyerOrderQueryPO filter = new BuyerOrderQueryPO();
        filter.setBuyerUsername(BUYER_A);
        return filter;
    }

    private OrderPO findById(List<OrderPO> rows, int id) {
        for (OrderPO row : rows) {
            if (row.getId() == id) {
                return row;
            }
        }
        return null;
    }

    // ============ 1. 字段级校验：buyer_fav=1 / shop_phone 有值读回一致 ============

    @Test
    void 买家分页_读回buyerFav与shopPhone() {
        List<OrderPO> rows = orderMapper.selectBuyerPage(filterA(), 0, 100);

        assertEquals(2, rows.size(), "买家 A 应有 2 条未删订单");

        OrderPO favPhone = findById(rows, aFavPhone);
        assertNotNull(favPhone, "应含收藏+有电话的订单");
        assertEquals(1, favPhone.getBuyerFav(), "buyerFav 应读回 1");
        assertEquals("13800000001", favPhone.getShopPhone(), "shopPhone 应读回快照值");
    }

    // ============ 2. 类型与格式：buyer_fav 默认 0 / shop_phone 为 null ============

    @Test
    void 买家分页_默认fav为0且无电话为null() {
        List<OrderPO> rows = orderMapper.selectBuyerPage(filterA(), 0, 100);

        OrderPO noFavNoPhone = findById(rows, aNoFavNoPhone);
        assertNotNull(noFavNoPhone, "应含未收藏+无电话的订单");
        assertEquals(0, noFavNoPhone.getBuyerFav(), "未收藏 buyerFav 应读回 0 而非 null");
        assertNull(noFavNoPhone.getShopPhone(), "无电话 shopPhone 应读回 null");
    }

    // ============ 3. 重复创建：重复查询两列值一致 ============

    @Test
    void 重复查询_两列值一致() {
        List<OrderPO> first = orderMapper.selectBuyerPage(filterA(), 0, 100);
        List<OrderPO> second = orderMapper.selectBuyerPage(filterA(), 0, 100);

        OrderPO f1 = findById(first, aFavPhone);
        OrderPO f2 = findById(second, aFavPhone);
        assertEquals(f1.getBuyerFav(), f2.getBuyerFav(), "重复查询 buyerFav 应一致");
        assertEquals(f1.getShopPhone(), f2.getShopPhone(), "重复查询 shopPhone 应一致");
    }

    // ============ 4. 非法输入：他人/已删不出现 + 两列不影响 count ============

    @Test
    void 他人订单_不出现() {
        List<OrderPO> rows = orderMapper.selectBuyerPage(filterA(), 0, 100);

        for (OrderPO row : rows) {
            assertEquals(BUYER_A, row.getBuyerUsername(), "不得出现买家 B 订单");
        }
        assertEquals(2, rows.size(), "买家 A 仅 2 条");
    }

    @Test
    void 两列读取_不影响countBuyerByFilter总数() {
        int total = orderMapper.countBuyerByFilter(filterA());

        assertEquals(2, total, "countBuyerByFilter 总数应仍为 2（两列读取不改变计数口径）");
    }

    static Stream<Arguments> buyerFavCases() {
        return Stream.of(
                arguments("已收藏读回1", 1, 1),
                arguments("未收藏读回0", 0, 0)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("buyerFavCases")
    void buyerFav_读回0或1(String caseName, int writtenFav, int expectedFav) throws Exception {
        int id = insertOrder(BUYER_A, "PENDING", writtenFav, null);

        List<OrderPO> rows = orderMapper.selectBuyerPage(filterA(), 0, 100);
        OrderPO row = findById(rows, id);

        assertNotNull(row, caseName + "：应读到该订单");
        assertEquals(expectedFav, row.getBuyerFav(), caseName + "：buyerFav 读回不正确");
    }
}
