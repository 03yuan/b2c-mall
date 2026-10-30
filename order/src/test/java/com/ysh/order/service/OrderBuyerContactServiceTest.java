package com.ysh.order.service;

import com.ysh.order.dao.OrderMapper;
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
import com.ysh.order.web.vo.BuyerContactVO;

import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 联系买家（GET /order/{orderId}/buyer-contact）的 Service 层真实 SQLite 测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 9.2：
 * - 9.2.1 返回 {buyerUsername, maskedPhone}，供前端唤起聊天窗口；shopId 隔离，他店 -> 404
 *   （Fail 边界：返回明文手机号 -> 隐私泄漏；buyerUsername 为 null（历史单未写入）-> 空串而非 500）
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：买家账号正确、手机号脱敏（非明文）
 * 2. 类型与格式：手机号脱敏边界（11 位 / 短号）
 * 3. 重复创建：同一订单重复查询结果一致（幂等）
 * 4. 非法输入：历史单 buyerUsername 为 null -> 空串；他店/不存在 -> 抛 IllegalArgumentException
 *
 * 真实临时 SQLite 库 + 真实 MyBatis Mapper + 真实 OrderServiceImpl。
 */
class OrderBuyerContactServiceTest {

    private static final Integer SHOP_A = 1;
    private static final Integer SHOP_B = 2;

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private OrderMapper orderMapper;
    private OrderServiceImpl orderService;
    private int orderNoSeq = 0;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "order-buyer-contact.db").getAbsolutePath();
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
                    + "logistics_no VARCHAR(64),"
                    + "logistics_type VARCHAR(20),"
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

    private int insertOrder(int shopId, String receiverPhone, String buyerUsername) throws Exception {
        orderNoSeq++;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                             + " status, receiver_name, receiver_phone, receiver_address, buyer_username)"
                             + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "NO-" + orderNoSeq);
            ps.setInt(2, shopId);
            ps.setInt(3, 100);
            ps.setString(4, "测试商品");
            ps.setInt(5, 2);
            ps.setBigDecimal(6, new BigDecimal("39.80"));
            ps.setString(7, "PAID");
            ps.setString(8, "张三");
            ps.setString(9, receiverPhone);
            ps.setString(10, "上海市浦东新区测试路 1 号");
            ps.setString(11, buyerUsername);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    // ============ 1. 字段级校验：账号正确 + 手机号脱敏 ============

    @Test
    void 联系买家_账号正确且手机号脱敏非明文() throws Exception {
        int orderId = insertOrder(SHOP_A, "13800005678", "buyer_a");

        BuyerContactVO contact = orderService.getBuyerContact(orderId, SHOP_A);

        assertEquals("buyer_a", contact.getBuyerUsername(), "buyerUsername 应正确");
        assertEquals("138****5678", contact.getMaskedPhone(), "手机号必须脱敏为 138****5678");
    }

    // ============ 2. 类型与格式：手机号脱敏边界 ============

    /**
     * 列：用例名、原始手机号、期望脱敏结果
     */
    static Stream<Arguments> maskCases() {
        return Stream.of(
                arguments("11位手机号前3后4", "13800005678", "138****5678"),
                arguments("7位短号前2后2", "1234567", "12****67")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("maskCases")
    void 手机号脱敏_边界表格驱动(String caseName, String raw, String expected) throws Exception {
        int orderId = insertOrder(SHOP_A, raw, "buyer_a");

        BuyerContactVO contact = orderService.getBuyerContact(orderId, SHOP_A);

        assertEquals(expected, contact.getMaskedPhone(), caseName + "：脱敏结果不符");
    }

    // ============ 3. 重复创建：重复查询一致 ============

    @Test
    void 同一订单重复查询_结果一致() throws Exception {
        int orderId = insertOrder(SHOP_A, "13800005678", "buyer_a");

        BuyerContactVO first = orderService.getBuyerContact(orderId, SHOP_A);
        BuyerContactVO second = orderService.getBuyerContact(orderId, SHOP_A);

        assertEquals(first.getBuyerUsername(), second.getBuyerUsername(), "重复查询账号应一致");
        assertEquals(first.getMaskedPhone(), second.getMaskedPhone(), "重复查询脱敏结果应一致");
    }

    // ============ 4. 非法输入：历史单 null 账号 / 他店 / 不存在 ============

    @Test
    void 历史单buyerUsername为null_返回空串而非500() throws Exception {
        int orderId = insertOrder(SHOP_A, "13800005678", null);

        BuyerContactVO contact = orderService.getBuyerContact(orderId, SHOP_A);

        assertEquals("", contact.getBuyerUsername(), "历史单未写入买家账号时应返回空串");
    }

    @Test
    void 他店订单_抛参数异常() throws Exception {
        int orderId = insertOrder(SHOP_B, "13800005678", "buyer_b");

        assertThrows(IllegalArgumentException.class,
                () -> orderService.getBuyerContact(orderId, SHOP_A),
                "店铺 A 不得获取店铺 B 订单的联系信息");
    }

    @Test
    void 订单不存在_抛参数异常() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.getBuyerContact(999999, SHOP_A),
                "不存在的订单应抛 IllegalArgumentException");
    }
}
