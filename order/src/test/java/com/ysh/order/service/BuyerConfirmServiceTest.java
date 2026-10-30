package com.ysh.order.service;

import com.ysh.order.dao.OrderLogMapper;
import com.ysh.order.dao.OrderMapper;
import com.ysh.order.dao.po.OrderLogPO;
import com.ysh.order.dao.po.OrderPO;
import com.ysh.order.mq.OrderEventPublisher;
import com.ysh.order.service.impl.OrderServiceImpl;
import com.ysh.order.statemachine.OrderStateFactory;
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
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 买家确认收货（confirmByBuyer）的 Service 真库测试（TDD 红阶段）。
 * 对应 .tdd/task/mall-order-manage.md 功能上下文 8.1（Service 层 - confirmByBuyer 复用重构）：
 * - 8.1.1 提取 doConfirm(order, operatorId) 私有方法（transitionState("CONFIRM") + CONFIRM_RECEIPT 事件），
 *          买家版 confirmByBuyer(orderId, username) = 归属校验（同 7.1.2 口径）+ doConfirm
 * - 8.1.2 非 SHIPPED（PENDING/PAID/COMPLETED/FAILED）-> 状态机抛 IllegalStateException；他人/已删 -> 404 零改动
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：本人 SHIPPED -> status=COMPLETED
 * 2. 类型与格式：日志 event=CONFIRM、CONFIRM_RECEIPT 事件发布
 * 3. 重复确认：重复确认 COMPLETED -> 拒绝零改动
 * 4. 非法输入：他人/不存在/已删 -> 404 零改动；非 SHIPPED 状态 -> 拒绝零改动
 */
class BuyerConfirmServiceTest {

    private static final String BUYER_A = "buyer_a";
    private static final String BUYER_B = "buyer_b";

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private OrderMapper orderMapper;
    private OrderLogMapper orderLogMapper;
    private OrderEventPublisher orderEventPublisher;
    private OrderServiceImpl orderService;
    private int orderNoSeq = 0;

    private int aShipped;    // 买家 A 未删 SHIPPED
    private int aDeleted;    // 买家 A 已删 SHIPPED
    private int bShipped;    // 买家 B 未删 SHIPPED

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "buyer-confirm-service.db").getAbsolutePath();
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
        orderLogMapper = mock(OrderLogMapper.class);
        orderEventPublisher = mock(OrderEventPublisher.class);

        orderService = new OrderServiceImpl();
        ReflectionTestUtils.setField(orderService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(orderService, "orderLogMapper", orderLogMapper);
        ReflectionTestUtils.setField(orderService, "stateFactory", new OrderStateFactory());
        ReflectionTestUtils.setField(orderService, "orderEventPublisher", orderEventPublisher);

        aShipped = insertOrder(BUYER_A, 0, "SHIPPED");
        aDeleted = insertOrder(BUYER_A, 1, "SHIPPED");
        bShipped = insertOrder(BUYER_B, 0, "SHIPPED");
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private int insertOrder(String buyerUsername, int buyerDeleted, String status) throws Exception {
        orderNoSeq++;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                             + " status, receiver_name, receiver_phone, receiver_address,"
                             + " buyer_username, buyer_deleted)"
                             + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "NO-" + orderNoSeq);
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
            ps.setInt(12, buyerDeleted);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private OrderPO reload(int id) {
        return orderMapper.selectById(id);
    }

    // ============ 1. 字段级校验：本人 SHIPPED -> COMPLETED ============

    @Test
    void 本人待收货订单确认_状态为COMPLETED() {
        orderService.confirmByBuyer(aShipped, BUYER_A);

        assertEquals("COMPLETED", reload(aShipped).getStatus(), "确认收货后 status 应为 COMPLETED");
    }

    // ============ 2. 类型与格式：日志 event=CONFIRM + CONFIRM_RECEIPT 事件发布 ============

    @Test
    void 本人待收货订单确认_写CONFIRM日志且发布确认收货事件() {
        orderService.confirmByBuyer(aShipped, BUYER_A);

        ArgumentCaptor<OrderLogPO> logCaptor = ArgumentCaptor.forClass(OrderLogPO.class);
        verify(orderLogMapper).insert(logCaptor.capture());
        assertEquals("CONFIRM", logCaptor.getValue().getEvent(), "日志事件名必须为 CONFIRM");
        assertEquals("SHIPPED", logCaptor.getValue().getFromStatus(), "日志 fromStatus 应为 SHIPPED");
        assertEquals("COMPLETED", logCaptor.getValue().getToStatus(), "日志 toStatus 应为 COMPLETED");

        ArgumentCaptor<OrderPO> eventCaptor = ArgumentCaptor.forClass(OrderPO.class);
        verify(orderEventPublisher).publishConfirmReceipt(eventCaptor.capture());
        assertEquals(aShipped, eventCaptor.getValue().getId(), "事件载荷 orderId 不正确");
    }

    // ============ 3. 重复确认：重复确认 COMPLETED -> 拒绝零改动 ============

    @Test
    void 已完成订单重复确认_拒绝且零改动零事件() {
        orderService.confirmByBuyer(aShipped, BUYER_A);

        assertThrows(IllegalStateException.class,
                () -> orderService.confirmByBuyer(aShipped, BUYER_A),
                "已完成订单重复确认必须抛 IllegalStateException");

        assertEquals("COMPLETED", reload(aShipped).getStatus(), "重复确认后 status 不应改变");
    }

    // ============ 4. 非法输入：他人/不存在/已删 404 + 状态边界 ============

    @Test
    void 确认他人订单_抛订单不存在且零改动() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.confirmByBuyer(bShipped, BUYER_A),
                "确认他人订单应抛订单不存在");
        assertEquals("SHIPPED", reload(bShipped).getStatus(), "他人订单状态不得被改动");
    }

    @Test
    void 确认不存在订单_抛订单不存在() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.confirmByBuyer(99999, BUYER_A),
                "确认不存在订单应抛订单不存在");
    }

    @Test
    void 确认已删订单_抛订单不存在() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.confirmByBuyer(aDeleted, BUYER_A),
                "确认已删订单应抛订单不存在");
    }

    /**
     * 列：用例名、订单初始状态
     */
    static Stream<Arguments> nonShippedStatusCases() {
        return Stream.of(
                arguments("PENDING", "PENDING"),
                arguments("PAID", "PAID"),
                arguments("COMPLETED", "COMPLETED"),
                arguments("FAILED", "FAILED")
        );
    }

    @ParameterizedTest(name = "非SHIPPED状态 {0}")
    @MethodSource("nonShippedStatusCases")
    void 非待收货状态确认_拒绝且零改动零事件(String caseName, String status) throws Exception {
        int id = insertOrder(BUYER_A, 0, status);

        assertThrows(IllegalStateException.class,
                () -> orderService.confirmByBuyer(id, BUYER_A),
                caseName + "：非 SHIPPED 状态确认应抛 IllegalStateException");

        assertEquals(status, reload(id).getStatus(), caseName + "：状态不得被改动");
        verify(orderEventPublisher, never()).publishConfirmReceipt(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(orderLogMapper);
    }
}
