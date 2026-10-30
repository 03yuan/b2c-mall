package com.ysh.order.service;

import com.ysh.order.dao.OrderLogMapper;
import com.ysh.order.dao.OrderMapper;
import com.ysh.order.dao.po.OrderLogPO;
import com.ysh.order.dao.po.OrderPO;
import com.ysh.order.mq.OrderEventPublisher;
import com.ysh.order.service.impl.BalanceStrategyService;
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
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 买家去支付（payByBuyer）的 Service 真库测试（TDD 红阶段）。
 * 对应 .tdd/task/mall-order-manage.md 功能上下文 7.1（Service 层 - payByBuyer 复用重构）：
 * - 7.1.1 提取 doPay 私有方法，商家 pay 与买家 payByBuyer 共用（绿阶段重构）
 * - 7.1.2 payByBuyer(orderId, payType, username)：先以 id+buyer_username+buyer_deleted=0 查归属，
 *          无 -> 404；本人 PENDING -> doPay 成功落 pay_type/pay_time；事件载荷含 skuId/quantity
 * - 7.1.3 状态边界：非 PENDING 拒绝且零改动；payType 非法 -> 400「不支持的支付方式」
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：本人 PENDING -> status=PAID、pay_type=BALANCE、pay_time 非空
 * 2. 类型与格式：日志 event=PAY、PAY_SUCCESS 事件载荷含 skuId/quantity
 * 3. 重复创建：重复支付 PAID -> 拒绝零改动
 * 4. 非法输入：他人/不存在/已删 -> 404 零改动；非 PENDING 状态 -> 拒绝；非法 payType -> 400
 */
class BuyerPayServiceTest {

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

    private int aPending;     // 买家 A 未删 PENDING
    private int aDeleted;     // 买家 A 已删
    private int bPending;     // 买家 B 未删

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "buyer-pay-service.db").getAbsolutePath();
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
        ReflectionTestUtils.setField(orderService, "payStrategies", List.of(new BalanceStrategyService()));
        ReflectionTestUtils.setField(orderService, "orderEventPublisher", orderEventPublisher);

        aPending = insertOrder(BUYER_A, 0, "PENDING");
        aDeleted = insertOrder(BUYER_A, 1, "PENDING");
        bPending = insertOrder(BUYER_B, 0, "PENDING");
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

    // ============ 1. 字段级校验：本人 PENDING -> PAID + pay_type + pay_time ============

    @Test
    void 本人待付款订单支付_状态为PAID且payType与payTime落库() {
        orderService.payByBuyer(aPending, "BALANCE", BUYER_A);

        OrderPO po = reload(aPending);
        assertEquals("PAID", po.getStatus(), "支付后 status 应为 PAID");
        assertEquals("BALANCE", po.getPayType(), "pay_type 应落库 BALANCE");
        assertNotNull(po.getPayTime(), "pay_time 应非空（支付时间落库）");
    }

    // ============ 2. 类型与格式：日志 event=PAY + PAY_SUCCESS 事件载荷 ============

    @Test
    void 本人待付款订单支付_写PAY日志且事件载荷含skuId与quantity() {
        orderService.payByBuyer(aPending, "BALANCE", BUYER_A);

        ArgumentCaptor<OrderLogPO> logCaptor = ArgumentCaptor.forClass(OrderLogPO.class);
        verify(orderLogMapper).insert(logCaptor.capture());
        assertEquals("PAY", logCaptor.getValue().getEvent(), "日志事件名必须为 PAY");

        ArgumentCaptor<OrderPO> eventCaptor = ArgumentCaptor.forClass(OrderPO.class);
        verify(orderEventPublisher).publishPaySuccess(eventCaptor.capture());
        assertEquals(100, eventCaptor.getValue().getSkuId(), "事件载荷 skuId 不正确（销量回写依赖）");
        assertEquals(2, eventCaptor.getValue().getQuantity(), "事件载荷 quantity 不正确（销量回写依赖）");
    }

    // ============ 3. 重复创建：重复支付 PAID -> 拒绝零改动 ============

    @Test
    void 已付款订单重复支付_拒绝且零改动零事件() {
        orderService.payByBuyer(aPending, "BALANCE", BUYER_A);
        String payTimeBefore = reload(aPending).getPayTime();

        assertThrows(IllegalStateException.class,
                () -> orderService.payByBuyer(aPending, "BALANCE", BUYER_A),
                "已付款订单重复支付必须抛 IllegalStateException");

        OrderPO po = reload(aPending);
        assertEquals("PAID", po.getStatus(), "重复支付后 status 不应改变");
        assertEquals("BALANCE", po.getPayType(), "重复支付后 pay_type 不应改变");
        assertEquals(payTimeBefore, po.getPayTime(), "重复支付后 pay_time 不应被覆盖");
    }

    // ============ 4. 非法输入：他人/不存在/已删 404 + 状态边界 + 非法 payType ============

    @Test
    void 支付他人订单_抛订单不存在且零改动() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.payByBuyer(bPending, "BALANCE", BUYER_A),
                "支付他人订单应抛订单不存在");
        assertEquals("PENDING", reload(bPending).getStatus(), "他人订单状态不得被改动");
        assertNull(reload(bPending).getPayTime(), "他人订单 pay_time 不得被改动");
    }

    @Test
    void 支付不存在订单_抛订单不存在() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.payByBuyer(99999, "BALANCE", BUYER_A),
                "支付不存在订单应抛订单不存在");
    }

    @Test
    void 支付已删订单_抛订单不存在() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.payByBuyer(aDeleted, "BALANCE", BUYER_A),
                "支付已删订单应抛订单不存在");
    }

    /**
     * 列：用例名、订单初始状态
     */
    static Stream<Arguments> nonPendingStatusCases() {
        return Stream.of(
                arguments("FAILED", "FAILED"),
                arguments("SHIPPED", "SHIPPED"),
                arguments("COMPLETED", "COMPLETED")
        );
    }

    @ParameterizedTest(name = "非PENDING状态 {0}")
    @MethodSource("nonPendingStatusCases")
    void 非待付款状态支付_拒绝且零改动(String caseName, String status) throws Exception {
        int id = insertOrder(BUYER_A, 0, status);

        assertThrows(IllegalStateException.class,
                () -> orderService.payByBuyer(id, "BALANCE", BUYER_A),
                caseName + "：非 PENDING 状态支付应抛 IllegalStateException");

        assertEquals(status, reload(id).getStatus(), caseName + "：状态不得被改动");
        assertNull(reload(id).getPayTime(), caseName + "：pay_time 不得被改动");
    }

    /**
     * 列：用例名、非法支付方式
     */
    static Stream<Arguments> invalidPayTypeCases() {
        return Stream.of(
                arguments("未支持的支付方式", "CASH"),
                arguments("空字符串", ""),
                arguments("纯空格", "   "),
                arguments("null", null)
        );
    }

    @ParameterizedTest(name = "非法 payType {0}")
    @MethodSource("invalidPayTypeCases")
    void 非法支付方式_拒绝且零改动零事件(String caseName, String payType) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> orderService.payByBuyer(aPending, payType, BUYER_A),
                caseName + "：应抛 IllegalArgumentException");
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("支付方式"),
                caseName + "：异常消息应提示支付方式");

        assertEquals("PENDING", reload(aPending).getStatus(), caseName + "：状态不得被改动");
        assertNull(reload(aPending).getPayTime(), caseName + "：pay_time 不得被改动");
        verify(orderEventPublisher, never()).publishPaySuccess(any());
        verifyNoInteractions(orderLogMapper);
    }
}
