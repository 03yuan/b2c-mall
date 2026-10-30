package com.ysh.order.service;

import com.ysh.order.dao.OrderLogMapper;
import com.ysh.order.dao.OrderMapper;
import com.ysh.order.dao.RefundMapper;
import com.ysh.order.dao.po.OrderLogPO;
import com.ysh.order.dao.po.OrderPO;
import com.ysh.order.service.impl.OrderServiceImpl;
import com.ysh.order.statemachine.OrderStateFactory;
import com.ysh.order.web.vo.OrderDetailVO;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 买家端订单详情（getBuyerOrderDetail）契约测试（TDD 红阶段）。
 * 对应 .tdd/task/mall-order-manage.md 功能上下文 10.1（订单详情弹窗/新页数据源【已实现·回归】）：
 * - 10.1.1 本人订单 -> OrderDetailVO 完整返回：行字段 + receiverAddress + logisticsNo/logisticsType + payTime + logs
 * - 10.1.2 他人订单/不存在 -> 404；未登录 -> 401（Controller 层，见 BuyerOrderActionControllerTest）
 *
 * 契约锁定点：已支付+已发货单必须断言 payTime 非空、logs（状态流转日志）齐全。
 * 现有 BuyerOrderActionServiceTest 只断言 logisticsNo/logisticsType/receiverAddress，未断言 payTime 与 logs，
 * 导致 selectByIdAndBuyerUsername 漏读 pay_time 列的缺陷未被发现。
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：本人已发货单 payTime 非空 + logisticsNo/logisticsType 齐全
 * 2. 类型与格式：logs 状态流转轨迹按时间升序（PAY -> SHIP）
 * 3. 重复查询：重复查询结果一致（幂等）
 * 4. 非法输入：未支付单 payTime null；他人/不存在 -> 404
 */
class BuyerOrderDetailContractTest {

    private static final String BUYER_A = "buyer_a";
    private static final String BUYER_B = "buyer_b";

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private OrderMapper orderMapper;
    private OrderLogMapper orderLogMapper;
    private RefundMapper refundMapper;
    private OrderServiceImpl orderService;
    private int orderNoSeq = 0;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "buyer-order-detail-contract.db").getAbsolutePath();
        dataSource = new UnpooledDataSource("org.sqlite.JDBC", jdbcUrl, null, null);

        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        for (String resource : Arrays.asList(
                "mapper/OrderMapper.xml", "mapper/OrderLogMapper.xml", "mapper/RefundMapper.xml")) {
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
            st.executeUpdate("DROP TABLE IF EXISTS tb_order_log");
            st.executeUpdate("CREATE TABLE tb_order_log ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "order_id INTEGER NOT NULL,"
                    + "from_status VARCHAR(20),"
                    + "to_status VARCHAR(20) NOT NULL,"
                    + "event VARCHAR(20) NOT NULL,"
                    + "operator_id INTEGER,"
                    + "remark VARCHAR(500),"
                    + "create_time DATETIME DEFAULT (datetime('now','localtime')))");
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
        orderLogMapper = session.getMapper(OrderLogMapper.class);
        refundMapper = session.getMapper(RefundMapper.class);

        orderService = new OrderServiceImpl();
        ReflectionTestUtils.setField(orderService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(orderService, "orderLogMapper", orderLogMapper);
        ReflectionTestUtils.setField(orderService, "refundMapper", refundMapper);
        ReflectionTestUtils.setField(orderService, "stateFactory", new OrderStateFactory());
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private int insertOrder(String buyerUsername, String status, String payTime) throws Exception {
        orderNoSeq++;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                             + " status, pay_type, pay_time, receiver_name, receiver_phone, receiver_address,"
                             + " buyer_username, buyer_deleted, logistics_type, logistics_no, unit_price)"
                             + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "NO-" + orderNoSeq);
            ps.setInt(2, 1);
            ps.setInt(3, 100);
            ps.setString(4, "测试商品");
            ps.setInt(5, 2);
            ps.setBigDecimal(6, new java.math.BigDecimal("39.80"));
            ps.setString(7, status);
            ps.setString(8, "WECHAT");
            ps.setString(9, payTime);
            ps.setString(10, "张三");
            ps.setString(11, "13800001111");
            ps.setString(12, "上海市浦东新区测试路 1 号");
            ps.setString(13, buyerUsername);
            ps.setInt(14, 0);
            ps.setString(15, "EXPRESS");
            ps.setString(16, "SF1234567890");
            ps.setBigDecimal(17, new java.math.BigDecimal("19.90"));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private void insertLog(int orderId, String fromStatus, String toStatus, String event) {
        OrderLogPO logEntry = new OrderLogPO();
        logEntry.setOrderId(orderId);
        logEntry.setFromStatus(fromStatus);
        logEntry.setToStatus(toStatus);
        logEntry.setEvent(event);
        logEntry.setOperatorId(0);
        logEntry.setRemark(event + ": " + fromStatus + " -> " + toStatus);
        orderLogMapper.insert(logEntry);
    }

    private OrderPO reload(int id) {
        return orderMapper.selectById(id);
    }

    // ============ 1. 字段级校验：本人已发货单 payTime 非空 + 物流齐全 ============

    @Test
    void 本人已发货订单详情_payTime非空且物流齐全() throws Exception {
        int id = insertOrder(BUYER_A, "SHIPPED", "2026-10-20 10:05:00");

        OrderDetailVO detail = orderService.getBuyerOrderDetail(id, BUYER_A);

        assertNotNull(detail.getPayTime(), "已支付订单 payTime 必须非空（详情页支付记录依赖）");
        assertEquals(LocalDateTime.of(2026, 10, 20, 10, 5, 0), detail.getPayTime(), "payTime 值不正确");
        assertEquals("SF1234567890", detail.getLogisticsNo(), "logisticsNo 必须齐全");
        assertEquals("EXPRESS", detail.getLogisticsType(), "logisticsType 必须齐全");
        assertEquals("上海市浦东新区测试路 1 号", detail.getReceiverAddress(), "receiverAddress 必须齐全");
    }

    // ============ 2. 类型与格式：logs 状态流转轨迹按时间升序 ============

    @Test
    void 状态流转日志_按时间升序且轨迹完整() throws Exception {
        int id = insertOrder(BUYER_A, "SHIPPED", "2026-10-20 10:05:00");
        insertLog(id, "PENDING", "PAID", "PAY");
        insertLog(id, "PAID", "SHIPPED", "SHIP");

        OrderDetailVO detail = orderService.getBuyerOrderDetail(id, BUYER_A);

        List<OrderLogPO> logs = detail.getLogs();
        assertEquals(2, logs.size(), "应有 PAY -> SHIP 两段流转日志");
        assertEquals("PAY", logs.get(0).getEvent(), "首条日志 event 应为 PAY");
        assertEquals("SHIP", logs.get(1).getEvent(), "次条日志 event 应为 SHIP");
    }

    // ============ 3. 重复查询：结果一致 ============

    @Test
    void 重复查询详情_结果一致() throws Exception {
        int id = insertOrder(BUYER_A, "SHIPPED", "2026-10-20 10:05:00");
        insertLog(id, "PENDING", "PAID", "PAY");

        OrderDetailVO first = orderService.getBuyerOrderDetail(id, BUYER_A);
        OrderDetailVO second = orderService.getBuyerOrderDetail(id, BUYER_A);

        assertEquals(first.getPayTime(), second.getPayTime(), "重复查询 payTime 应一致");
        assertEquals(first.getLogs().size(), second.getLogs().size(), "重复查询日志条数应一致");
    }

    // ============ 4. 非法输入：未支付 payTime null；他人/不存在 -> 404 ============

    @Test
    void 未支付订单详情_payTime为null() throws Exception {
        int id = insertOrder(BUYER_A, "PENDING", null);

        OrderDetailVO detail = orderService.getBuyerOrderDetail(id, BUYER_A);

        assertNull(detail.getPayTime(), "未支付订单 payTime 应为 null");
    }

    @Test
    void 他人订单详情_抛订单不存在() throws Exception {
        int otherId = insertOrder(BUYER_B, "SHIPPED", "2026-10-20 10:05:00");

        assertThrows(IllegalArgumentException.class,
                () -> orderService.getBuyerOrderDetail(otherId, BUYER_A),
                "买家 A 读买家 B 订单详情应抛订单不存在");
    }

    @Test
    void 不存在订单详情_抛订单不存在() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.getBuyerOrderDetail(999999, BUYER_A),
                "不存在订单详情应抛订单不存在");
    }
}
