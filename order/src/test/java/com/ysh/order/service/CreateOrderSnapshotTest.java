package com.ysh.order.service;

import com.ysh.order.dao.OrderMapper;
import com.ysh.order.dao.po.OrderPO;
import com.ysh.order.service.impl.OrderServiceImpl;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * createOrder 落库写入四个快照字段的 Service 层真库测试（TDD 红阶段）。
 * 对应 .tdd/task/mall-order-manage.md 功能上下文 5.2（Service 层 - createOrder 写入快照）：
 * - 5.2.1 createOrder 落库时写入 shop_name=shopName、sku_image=skuImage、shop_phone=shopPhone、
 *        unit_price=price（与 sku_name 同「信任请求快照」策略，不做店铺/商品校验）
 *   （Fail 边界：快照不写 -> 电话图标点击无号、店铺名空白、单价 undefined）
 * - 5.2.2 新字段为 null/空串时落库 null 且创建流程不报错（向后兼容存量前端调用方）
 *   （Fail 边界：null 处理崩溃 -> 老客户端下单全线失败）
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：带 shopName/skuImage/shopPhone 创建 -> DB 读回四字段一致，unit_price == price
 * 2. 类型与格式：unit_price 为 BigDecimal 且与 price compareTo==0；shopName/skuImage/shopPhone 为 String 原样
 * 3. 重复创建：重复下单各自写各自快照（互不串）
 * 4. 非法输入：新字段为 null/空串 -> 落库 null 且流程不报错（向后兼容）
 */
class CreateOrderSnapshotTest {

    private static final String BUYER_A = "buyer_a";

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private OrderMapper orderMapper;
    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "create-snapshot.db").getAbsolutePath();
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

        orderService = new OrderServiceImpl();
        ReflectionTestUtils.setField(orderService, "orderMapper", orderMapper);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private int create(String shopName, String skuImage, String shopPhone) {
        Map<String, Object> result = orderService.createOrder(
                1, 1, 100, "测试商品", 2, new BigDecimal("39.80"),
                "张三", "13800001111", "上海市浦东新区测试路 1 号",
                shopName, skuImage, shopPhone,
                BUYER_A);
        return (Integer) result.get("orderId");
    }

    private OrderPO readById(int id) {
        return orderMapper.selectById(id);
    }

    // ============ 1. 字段级校验：带三字段创建 -> DB 读回四字段一致 ============

    @Test
    void 带快照字段创建_DB读回四字段一致() {
        int id = create("测试店铺", "https://img.example.com/a.png", "13800000001");

        OrderPO po = readById(id);
        assertNotNull(po, "应能读回创建的订单");
        assertEquals("测试店铺", po.getShopName(), "shop_name 应写入快照");
        assertEquals("https://img.example.com/a.png", po.getSkuImage(), "sku_image 应写入快照");
        assertEquals("13800000001", po.getShopPhone(), "shop_phone 应写入快照");
        assertEquals(0, new BigDecimal("39.80").compareTo(po.getUnitPrice()),
                "unit_price 应等于 price（39.80）");
    }

    // ============ 2. 类型与格式：unit_price == price 且为 BigDecimal ============

    @Test
    void unitPrice与price相等() {
        int id = create("店铺A", "img1.png", "13800000001");

        OrderPO po = readById(id);
        assertNotNull(po.getUnitPrice(), "unit_price 不应为 null");
        assertEquals(0, new BigDecimal("39.80").compareTo(po.getUnitPrice()),
                "unit_price 应与下单 price 完全相等");
    }

    // ============ 3. 重复创建：重复下单各自写各自快照 ============

    @Test
    void 重复下单_各自写各自快照() {
        int id1 = create("店铺A", "imgA.png", "13800000001");
        int id2 = create("店铺B", "imgB.png", "13800000002");

        OrderPO po1 = readById(id1);
        OrderPO po2 = readById(id2);
        assertEquals("店铺A", po1.getShopName(), "第 1 单 shop_name 应为店铺A");
        assertEquals("店铺B", po2.getShopName(), "第 2 单 shop_name 应为店铺B");
        assertEquals("imgA.png", po1.getSkuImage(), "第 1 单 sku_image 应为 imgA");
        assertEquals("imgB.png", po2.getSkuImage(), "第 2 单 sku_image 应为 imgB");
        assertEquals("13800000001", po1.getShopPhone(), "第 1 单 shop_phone 应为 1 号");
        assertEquals("13800000002", po2.getShopPhone(), "第 2 单 shop_phone 应为 2 号");
    }

    // ============ 4. 非法输入：新字段为 null/空串 -> 落库 null 且不报错（向后兼容） ============

    static Stream<Arguments> nullishCases() {
        return Stream.of(
                arguments("三字段全null", null, null, null),
                arguments("三字段全空串", "", "", ""),
                arguments("shopName空串其余null", "", null, null)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nullishCases")
    void 新字段null或空串_落库null且不报错(String caseName, String shopName, String skuImage, String shopPhone) {
        int id = create(shopName, skuImage, shopPhone);

        OrderPO po = readById(id);
        assertNotNull(po, caseName + "：应能正常创建订单");
        assertNull(po.getShopName(), caseName + "：shop_name 应落库 null");
        assertNull(po.getSkuImage(), caseName + "：sku_image 应落库 null");
        assertNull(po.getShopPhone(), caseName + "：shop_phone 应落库 null");
        // unit_price 复用已有 price 字段写入（5.1.1），恒等于必填 price，非「新字段」，不做 null 断言
        assertEquals(0, new BigDecimal("39.80").compareTo(po.getUnitPrice()),
                caseName + "：unit_price 应恒等于 price");
    }

    // 直接读 DB 原始列，验证 insert 列清单确实含四快照列（绕过 PO 映射可能掩盖的问题）
    @Test
    void 直读DB列_四快照列已落库() throws Exception {
        int id = create("测试店铺", "https://img.example.com/a.png", "13800000001");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT shop_name, sku_image, shop_phone, unit_price FROM tb_order WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals("测试店铺", rs.getString("shop_name"), "DB 列 shop_name 应落库");
                assertEquals("https://img.example.com/a.png", rs.getString("sku_image"), "DB 列 sku_image 应落库");
                assertEquals("13800000001", rs.getString("shop_phone"), "DB 列 shop_phone 应落库");
                assertEquals(0, new BigDecimal("39.80").compareTo(rs.getBigDecimal("unit_price")),
                        "DB 列 unit_price 应落库 39.80");
            }
        }
    }
}
