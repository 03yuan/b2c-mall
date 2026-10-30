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
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 商家端订单详情（增强 GET /order/{orderId}）的 Service 层真实 SQLite 测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 9.1：
 * - 9.1.1 DetailVO 返回完整明细：行 VO 全字段 + receiverAddress、logistics_no/logistics_type、pay_time
 *   + 状态流转日志列表（tb_order_log 按时间升序）；shopId 隔离，他店/不存在 -> 404
 *   （Fail 边界：详情缺物流/支付时间 -> 商家履约核对残缺；越权读详情）
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：DetailVO 字段齐全（继承行 VO 字段 + 收货地址/物流/支付时间）
 * 2. 类型与格式：状态流转日志按时间升序（PENDING->PAID->SHIPPED 各节点 event 序列正确）
 * 3. 重复创建：同一订单重复查询详情结果一致（幂等）
 * 4. 非法输入：他店订单 / 不存在订单 -> 抛 IllegalArgumentException（映射 404）
 *
 * 真实临时 SQLite 库 + 真实 MyBatis Mapper + 真实 OrderServiceImpl。
 */
class OrderDetailServiceTest {

    private static final Integer SHOP_A = 1;
    private static final Integer SHOP_B = 2;

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
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "order-detail.db").getAbsolutePath();
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

    /** 插入一条已发货订单（含物流/支付时间/收货地址） */
    private int insertShippedOrder(int shopId) throws Exception {
        orderNoSeq++;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                             + " status, pay_type, pay_time, receiver_name, receiver_phone, receiver_address,"
                             + " buyer_username, logistics_type, logistics_no, store_name, unit_price, is_gift)"
                             + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "NO-" + orderNoSeq);
            ps.setInt(2, shopId);
            ps.setInt(3, 100);
            ps.setString(4, "测试商品");
            ps.setInt(5, 2);
            ps.setBigDecimal(6, new BigDecimal("39.80"));
            ps.setString(7, "SHIPPED");
            ps.setString(8, "WECHAT");
            ps.setString(9, "2026-10-20 10:05:00");
            ps.setString(10, "张三");
            ps.setString(11, "13800005678");
            ps.setString(12, "上海市浦东新区测试路 1 号");
            ps.setString(13, "buyer_a");
            ps.setString(14, "EXPRESS");
            ps.setString(15, "SF001");
            ps.setString(16, "一号门店");
            ps.setBigDecimal(17, new BigDecimal("19.90"));
            ps.setInt(18, 0);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private void insertLog(int orderId, String fromStatus, String toStatus, String event) throws Exception {
        OrderLogPO logEntry = new OrderLogPO();
        logEntry.setOrderId(orderId);
        logEntry.setFromStatus(fromStatus);
        logEntry.setToStatus(toStatus);
        logEntry.setEvent(event);
        logEntry.setOperatorId(9);
        logEntry.setRemark(event + ": " + fromStatus + " -> " + toStatus);
        orderLogMapper.insert(logEntry);
    }

    // ============ 1. 字段级校验：DetailVO 字段齐全 ============

    @Test
    void 详情字段齐全_含收货地址物流与支付时间() throws Exception {
        int orderId = insertShippedOrder(SHOP_A);

        OrderDetailVO detail = orderService.getOrderDetail(orderId, SHOP_A);

        // 继承行 VO 字段
        assertEquals("NO-" + orderNoSeq, detail.getOrderNo(), "orderNo");
        assertEquals("SHIPPED", detail.getStatus(), "status 原始枚举值");
        assertEquals("张三", detail.getReceiverName(), "receiverName");
        assertEquals("138****5678", detail.getReceiverPhoneMasked(), "列表口径手机号应脱敏");
        assertEquals(Integer.valueOf(2), detail.getTotalQuantity(), "共 N 件");
        // 额外字段
        assertEquals("上海市浦东新区测试路 1 号", detail.getReceiverAddress(), "receiverAddress 必须齐全");
        assertEquals("SF001", detail.getLogisticsNo(), "logisticsNo 必须齐全");
        assertEquals("EXPRESS", detail.getLogisticsType(), "logisticsType 必须齐全");
        assertEquals(LocalDateTime.of(2026, 10, 20, 10, 5, 0), detail.getPayTime(), "payTime 必须齐全");
    }

    // ============ 2. 类型与格式：状态流转日志按时间升序 ============

    @Test
    void 状态流转日志_按时间升序且轨迹完整() throws Exception {
        int orderId = insertShippedOrder(SHOP_A);
        insertLog(orderId, "PENDING", "PAID", "PAY");
        insertLog(orderId, "PAID", "SHIPPED", "SHIP");

        OrderDetailVO detail = orderService.getOrderDetail(orderId, SHOP_A);

        List<OrderLogPO> logs = detail.getLogs();
        assertEquals(2, logs.size(), "应有 PENDING->PAID->SHIPPED 两段流转日志");
        assertEquals("PAY", logs.get(0).getEvent(), "首条日志 event 应为 PAY");
        assertEquals("PENDING", logs.get(0).getFromStatus(), "首条 from_status");
        assertEquals("PAID", logs.get(0).getToStatus(), "首条 to_status");
        assertEquals("SHIP", logs.get(1).getEvent(), "次条日志 event 应为 SHIP");
        assertEquals("PAID", logs.get(1).getFromStatus(), "次条 from_status");
        assertEquals("SHIPPED", logs.get(1).getToStatus(), "次条 to_status");
    }

    @Test
    void 无日志订单_返回空日志列表而非null() throws Exception {
        int orderId = insertShippedOrder(SHOP_A);

        OrderDetailVO detail = orderService.getOrderDetail(orderId, SHOP_A);

        assertEquals(0, detail.getLogs().size(), "无日志时应返回空列表而非 null");
    }

    // ============ 3. 重复创建：重复查询结果一致 ============

    @Test
    void 同一订单重复查询_结果一致() throws Exception {
        int orderId = insertShippedOrder(SHOP_A);
        insertLog(orderId, "PENDING", "PAID", "PAY");

        OrderDetailVO first = orderService.getOrderDetail(orderId, SHOP_A);
        OrderDetailVO second = orderService.getOrderDetail(orderId, SHOP_A);

        assertEquals(first.getOrderNo(), second.getOrderNo(), "重复查询 orderNo 应一致");
        assertEquals(first.getReceiverAddress(), second.getReceiverAddress(), "重复查询地址应一致");
        assertEquals(first.getLogs().size(), second.getLogs().size(), "重复查询日志条数应一致");
        assertEquals(first.getLogs().get(0).getEvent(), second.getLogs().get(0).getEvent(), "重复查询日志内容应一致");
    }

    // ============ 4. 非法输入：他店 / 不存在 ============

    @Test
    void 他店订单_抛参数异常() throws Exception {
        int orderId = insertShippedOrder(SHOP_B);

        assertThrows(IllegalArgumentException.class,
                () -> orderService.getOrderDetail(orderId, SHOP_A),
                "店铺 A 不得读店铺 B 的订单详情");
    }

    @Test
    void 订单不存在_抛参数异常() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.getOrderDetail(999999, SHOP_A),
                "不存在的订单应抛 IllegalArgumentException");
    }
}
