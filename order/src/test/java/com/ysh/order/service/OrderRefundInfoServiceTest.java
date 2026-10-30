package com.ysh.order.service;

import com.ysh.order.dao.OrderMapper;
import com.ysh.order.dao.RefundMapper;
import com.ysh.order.service.impl.OrderServiceImpl;
import com.ysh.order.web.vo.RefundInfoVO;
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
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 退款信息（GET /order/{orderId}/refund）的 Service 层真实 SQLite 测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 9.3：
 * - 9.3.1 存在 tb_refund 记录 -> 返回 status/amount/create_time；无记录 -> 「暂无售后」语义（null），不报错；
 *   多条退款单取最新一条
 *   （Fail 边界：无退款单返回 404/500 -> 前端无法区分「无售后」与「接口异常」）
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：有退款单 -> status/amount/createTime 正确
 * 2. 类型与格式：多条退款单取最新一条
 * 3. 重复创建：同一订单重复查询结果一致（幂等）
 * 4. 非法输入：无退款单 -> null 不报错；他店/不存在 -> 抛 IllegalArgumentException
 *
 * 真实临时 SQLite 库 + 真实 MyBatis Mapper + 真实 OrderServiceImpl。
 */
class OrderRefundInfoServiceTest {

    private static final Integer SHOP_A = 1;
    private static final Integer SHOP_B = 2;

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private OrderMapper orderMapper;
    private RefundMapper refundMapper;
    private OrderServiceImpl orderService;
    private int orderNoSeq = 0;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "order-refund-info.db").getAbsolutePath();
        dataSource = new UnpooledDataSource("org.sqlite.JDBC", jdbcUrl, null, null);

        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        for (String resource : Arrays.asList("mapper/OrderMapper.xml", "mapper/RefundMapper.xml")) {
            try (InputStream in = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
            }
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
            st.executeUpdate("DROP TABLE IF EXISTS tb_refund");
            st.executeUpdate("CREATE TABLE tb_refund ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "order_id INTEGER NOT NULL,"
                    + "shop_id INTEGER NOT NULL,"
                    + "status VARCHAR(20) NOT NULL,"
                    + "amount DECIMAL(10,2),"
                    + "create_time DATETIME DEFAULT (datetime('now','localtime')))");
        }

        session = sqlSessionFactory.openSession(true);
        orderMapper = session.getMapper(OrderMapper.class);
        refundMapper = session.getMapper(RefundMapper.class);

        orderService = new OrderServiceImpl();
        ReflectionTestUtils.setField(orderService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(orderService, "refundMapper", refundMapper);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private int insertOrder(int shopId) throws Exception {
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
            ps.setString(9, "13800005678");
            ps.setString(10, "上海市浦东新区测试路 1 号");
            ps.setString(11, "buyer_a");
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private void insertRefund(int orderId, int shopId, String status, String amount) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_refund (order_id, shop_id, status, amount) VALUES (?,?,?,?)")) {
            ps.setInt(1, orderId);
            ps.setInt(2, shopId);
            ps.setString(3, status);
            ps.setBigDecimal(4, new BigDecimal(amount));
            ps.executeUpdate();
        }
    }

    // ============ 1. 字段级校验：有退款单返回详情 ============

    @Test
    void 有退款单_返回状态金额与时间() throws Exception {
        int orderId = insertOrder(SHOP_A);
        insertRefund(orderId, SHOP_A, "PROCESSING", "39.80");

        RefundInfoVO info = orderService.getRefundInfo(orderId, SHOP_A);

        assertEquals("PROCESSING", info.getStatus(), "退款状态应正确");
        assertEquals(0, new BigDecimal("39.80").compareTo(info.getAmount()), "退款金额应正确");
        org.junit.jupiter.api.Assertions.assertNotNull(info.getCreateTime(), "退款时间应非空");
    }

    // ============ 2. 类型与格式：多条退款单取最新一条 ============

    @Test
    void 多条退款单_取最新一条() throws Exception {
        int orderId = insertOrder(SHOP_A);
        insertRefund(orderId, SHOP_A, "PROCESSING", "10.00");
        insertRefund(orderId, SHOP_A, "DONE", "39.80");

        RefundInfoVO info = orderService.getRefundInfo(orderId, SHOP_A);

        assertEquals("DONE", info.getStatus(), "多条退款单应取最新一条（id 最大）");
        assertEquals(0, new BigDecimal("39.80").compareTo(info.getAmount()), "应取最新一条的金额");
    }

    // ============ 3. 重复创建：重复查询一致 ============

    @Test
    void 同一订单重复查询_结果一致() throws Exception {
        int orderId = insertOrder(SHOP_A);
        insertRefund(orderId, SHOP_A, "DONE", "39.80");

        RefundInfoVO first = orderService.getRefundInfo(orderId, SHOP_A);
        RefundInfoVO second = orderService.getRefundInfo(orderId, SHOP_A);

        assertEquals(first.getStatus(), second.getStatus(), "重复查询状态应一致");
        assertEquals(0, first.getAmount().compareTo(second.getAmount()), "重复查询金额应一致");
    }

    // ============ 4. 非法输入：无退款单 / 他店 / 不存在 ============

    @Test
    void 无退款单_返回null不报错() throws Exception {
        int orderId = insertOrder(SHOP_A);

        RefundInfoVO info = orderService.getRefundInfo(orderId, SHOP_A);

        assertNull(info, "无退款单应返回 null（暂无售后语义），不得抛 404/500");
    }

    @Test
    void 他店订单_抛参数异常() throws Exception {
        int orderId = insertOrder(SHOP_B);

        assertThrows(IllegalArgumentException.class,
                () -> orderService.getRefundInfo(orderId, SHOP_A),
                "店铺 A 不得查店铺 B 订单的退款信息");
    }

    @Test
    void 订单不存在_抛参数异常() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.getRefundInfo(999999, SHOP_A),
                "不存在的订单应抛 IllegalArgumentException");
    }
}
