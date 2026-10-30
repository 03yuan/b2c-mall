package com.ysh.order.dao;

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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * tb_order schema 扩展（buyer_fav / shop_phone）的真实 SQLite 测试（TDD 红阶段）。
 * 对应 .tdd/task/mall-order-manage.md 功能上下文 1.1（DAO/PO 层 - 表结构与实体）：
 * - 1.1.1 order.sql 新增 buyer_fav TINYINT DEFAULT 0、shop_phone VARCHAR(20)（先 DROP 再重建）
 *   （Fail 边界：漏字段 → no such column；忘 DROP 旧表 → 新字段静默丢失）
 * - 1.1.2 OrderPO 同步新增 buyerFav（Integer）、shopPhone（String）
 *   （Fail 边界：PO 漏属性 → MyBatis 映射丢列，卡片 fav/电话恒 null）
 * - 1.1.3 默认值约束：未显式传入时 buyer_fav=0（shop_phone 可空无需默认值）
 *   （Fail 边界：默认值缺失 → 「是否已收藏」null 与 false 混淆）
 * - 1.1.4 反向决策：本期不建索引（无 SQL 断言，注释固化）
 *
 * 测试库为 @TempDir 下的临时 SQLite；建表语句为绿阶段 order.sql 的 tb_order 投影（含新两列）。
 * 红阶段失败机制：OrderMapper.xml 的 selectById 尚未 SELECT buyer_fav/shop_phone，
 *   读回的 buyerFav/shopPhone 恒为 null → 断言失败（失败原因唯一：缺 mapper 列映射）。
 */
class OrderSchemaExtensionTest {

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private OrderMapper orderMapper;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "schema-ext.db").getAbsolutePath();
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
                    + "buyer_deleted TINYINT DEFAULT 0,"
                    + "buyer_fav TINYINT DEFAULT 0,"
                    + "order_type VARCHAR(20) DEFAULT 'NORMAL',"
                    + "order_category VARCHAR(20) DEFAULT 'NORMAL',"
                    + "logistics_type VARCHAR(20),"
                    + "logistics_no VARCHAR(64),"
                    + "store_name VARCHAR(100),"
                    + "shop_name VARCHAR(100),"
                    + "shop_phone VARCHAR(20),"
                    + "sku_image VARCHAR(500),"
                    + "unit_price DECIMAL(10,2),"
                    + "is_gift TINYINT DEFAULT 0,"
                    + "create_time DATETIME DEFAULT (datetime('now','localtime')),"
                    + "update_time DATETIME DEFAULT (datetime('now','localtime')))");
        }

        session = sqlSessionFactory.openSession(true);
        orderMapper = session.getMapper(OrderMapper.class);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    /**
     * 原生 JDBC 插入订单。cols/values 为额外列（如 buyer_fav, shop_phone），
     * 用于模拟「含/不含新字段」的下单落库，绕过 mapper insert（其列清单不含新字段，属功能点 5）。
     */
    private int rawInsert(String orderNo, String extraCols, String extraValues) throws Exception {
        String sql = "INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity,"
                + " total_price, status, buyer_username, receiver_name, receiver_phone, receiver_address"
                + (extraCols == null || extraCols.isEmpty() ? "" : ", " + extraCols)
                + ") VALUES ('" + orderNo + "', 1, 100, '测试商品', 2, 39.80, 'PENDING',"
                + " 'buyer_a', '张三', '13800001111', '上海市浦东新区测试路 1 号'"
                + (extraValues == null || extraValues.isEmpty() ? "" : ", " + extraValues)
                + ")";
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
            try (ResultSet keys = st.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private OrderPO selectById(int id) {
        return orderMapper.selectById(id);
    }

    // ============ 1. 字段级校验（1.1.1 + 1.1.2）：新字段写入后读回一致 ============

    static Stream<Arguments> fieldCases() {
        return Stream.of(
                arguments("已收藏含电话", 1, "13800000001"),
                arguments("未收藏含电话", 0, "13900000002")
        );
    }

    @ParameterizedTest(name = "新字段读写一致: {0}")
    @MethodSource("fieldCases")
    void 新字段写入后读回一致(String caseName, int fav, String phone) throws Exception {
        int id = rawInsert("NO-" + System.nanoTime(), "buyer_fav, shop_phone",
                fav + ", '" + phone + "'");

        OrderPO po = selectById(id);

        assertNotNull(po, "订单应存在");
        assertEquals(fav, po.getBuyerFav(), "buyerFav 应与写入值一致（" + caseName + "）");
        assertEquals(phone, po.getShopPhone(), "shopPhone 应与写入值一致（" + caseName + "）");
    }

    // ============ 2. 类型与格式（1.1.2）：buyerFav Integer / shopPhone String，shopPhone 可空 ============

    @Test
    void buyerFav为Integer_shopPhone为String() throws Exception {
        int id = rawInsert("NO-TYPE", "buyer_fav, shop_phone", "1, '13800000001'");
        OrderPO po = selectById(id);

        assertNotNull(po.getBuyerFav(), "buyerFav 应非 null（Integer）");
        assertEquals(Integer.valueOf(1), po.getBuyerFav(), "buyerFav 应为 Integer 1");
        assertEquals("13800000001", po.getShopPhone(), "shopPhone 应为 String 原样透出");
    }

    @Test
    void shopPhone可空_不传为null() throws Exception {
        int id = rawInsert("NO-NULL-PHONE", "buyer_fav", "1");
        OrderPO po = selectById(id);

        assertEquals(Integer.valueOf(1), po.getBuyerFav(), "只传 fav 时 phone 应可空");
        assertNull(po.getShopPhone(), "shop_phone 未传时应为 null（可空，非空串）");
    }

    // ============ 3. 重复创建：两条订单各自独立读回 ============

    @Test
    void 重复创建_两条订单fav与phone各自独立() throws Exception {
        int idA = rawInsert("NO-REP-A", "buyer_fav, shop_phone", "1, '13800000001'");
        int idB = rawInsert("NO-REP-B", "buyer_fav, shop_phone", "0, '13900000002'");

        OrderPO poA = selectById(idA);
        OrderPO poB = selectById(idB);

        assertEquals(Integer.valueOf(1), poA.getBuyerFav(), "订单A buyerFav=1");
        assertEquals("13800000001", poA.getShopPhone(), "订单A shopPhone");
        assertEquals(Integer.valueOf(0), poB.getBuyerFav(), "订单B buyerFav=0（互不串）");
        assertEquals("13900000002", poB.getShopPhone(), "订单B shopPhone");
    }

    // ============ 4. 非法输入 / 默认值约束（1.1.3）：buyer_fav 未传时为 0 而非 null ============

    @Test
    void 默认值_buyerFav未传时为0非null() throws Exception {
        int id = rawInsert("NO-DEFAULT", null, null);

        OrderPO po = selectById(id);

        assertEquals(Integer.valueOf(0), po.getBuyerFav(),
                "未显式传入 buyer_fav 时读回应为默认 0（而非 null）");
        assertNull(po.getShopPhone(), "shop_phone 未传时应为 null（可空）");
    }
}
