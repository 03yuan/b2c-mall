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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 买家端单订单操作（详情/删除/取消）Service 层真实 SQLite 测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 11.1：
 * - 11.1.1 买家订单详情：复用 DetailVO 组装，buyer_username 隔离，他人/不存在 -> 404
 * - 11.1.2 删除订单=逻辑删除：buyer_deleted=1，后台保留，重复删除幂等，他人 -> 404 零改动
 * - 11.1.3 取消订单：复用状态机 cancel，仅 PENDING -> FAILED，写 CANCEL 日志，他人 -> 404
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：详情字段齐全（含物流、支付记录、状态文案）
 * 2. 类型与格式：取消日志 event=CANCEL、from=PENDING、to=FAILED
 * 3. 重复创建：重复删除幂等；重复取消（FAILED 终态）抛异常
 * 4. 非法输入：他人订单/不存在 -> 404；PAID 取消 -> 400 级错误
 */
class BuyerOrderActionServiceTest {

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

    private int aPending;
    private int aPaid;
    private int aShipped;
    private int bPending;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "buyer-order-action.db").getAbsolutePath();
        dataSource = new UnpooledDataSource("org.sqlite.JDBC", jdbcUrl, null, null);

        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        for (String resource : Arrays.asList("mapper/OrderMapper.xml", "mapper/RefundMapper.xml",
                "mapper/OrderLogMapper.xml")) {
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
            st.executeUpdate("DROP TABLE IF EXISTS tb_refund");
            st.executeUpdate("CREATE TABLE tb_refund ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "order_id INTEGER NOT NULL,"
                    + "shop_id INTEGER NOT NULL,"
                    + "status VARCHAR(20) NOT NULL,"
                    + "amount DECIMAL(10,2),"
                    + "create_time DATETIME DEFAULT (datetime('now','localtime')))");
            st.executeUpdate("DROP TABLE IF EXISTS tb_order_log");
            st.executeUpdate("CREATE TABLE tb_order_log ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "order_id INTEGER NOT NULL,"
                    + "from_status VARCHAR(20),"
                    + "to_status VARCHAR(20),"
                    + "event VARCHAR(20),"
                    + "operator_id INTEGER,"
                    + "remark VARCHAR(500),"
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

        aPending = insertOrder(BUYER_A, "PENDING", "2026-10-22 10:00:00", null, 0);
        aPaid = insertOrder(BUYER_A, "PAID", "2026-10-23 10:00:00", "WECHAT", 0);
        aShipped = insertOrder(BUYER_A, "SHIPPED", "2026-10-24 10:00:00", "ALIPAY", 0);
        bPending = insertOrder(BUYER_B, "PENDING", "2026-10-25 10:00:00", null, 0);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private int insertOrder(String buyerUsername, String status, String createTime,
                            String payType, int buyerDeleted) throws Exception {
        orderNoSeq++;
        String orderNo = "NO-" + orderNoSeq;
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            String sql = "INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                    + " status, pay_type, pay_time, buyer_username, buyer_deleted, receiver_name, receiver_phone,"
                    + " receiver_address, shop_name, sku_image, unit_price, logistics_no, logistics_type, create_time)"
                    + " VALUES ('" + orderNo + "', 1, 100, '测试商品', 2, 39.80, "
                    + "'" + status + "', " + (payType == null ? "NULL" : "'" + payType + "'") + ", "
                    + (payType == null ? "NULL" : "'2026-10-23 10:05:00'") + ", "
                    + "'" + buyerUsername + "', " + buyerDeleted + ", "
                    + "'张三', '13800001111', '上海市浦东新区测试路 1 号', '测试店铺', "
                    + "'https://img.example.com/a.png', 19.90, "
                    + ("SHIPPED".equals(status) ? "'SF1234567890', 'EXPRESS'" : "NULL, NULL")
                    + ", '" + createTime + "')";
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

    // ============ 1. 字段级校验：详情字段齐全 ============

    @Test
    void 买家详情_本人订单字段齐全() {
        OrderDetailVO detail = orderService.getBuyerOrderDetail(aShipped, BUYER_A);

        assertNotNull(detail, "本人订单详情不应为 null");
        assertEquals(aShipped, detail.getOrderId(), "orderId 正确");
        assertEquals("SHIPPED", detail.getStatus(), "status 正确");
        assertEquals("SF1234567890", detail.getLogisticsNo(), "物流单号正确");
        assertEquals("EXPRESS", detail.getLogisticsType(), "物流类型正确");
        assertEquals("上海市浦东新区测试路 1 号", detail.getReceiverAddress(), "收货地址正确");
        assertNotNull(detail.getItems(), "items 非空");
        assertEquals(1, detail.getItems().size(), "items 为数组且恒 1 条");
    }

    @Test
    void 买家详情_未支付订单支付时间为null() {
        OrderDetailVO detail = orderService.getBuyerOrderDetail(aPending, BUYER_A);

        assertNotNull(detail, "本人订单详情不应为 null");
        assertNull(detail.getPayTime(), "未支付订单支付时间应为 null");
    }

    // ============ 2. 类型与格式：取消日志正确 ============

    @Test
    void 取消_写CANCEL日志() {
        orderService.cancelByBuyer(aPending, BUYER_A);

        OrderPO order = selectById(aPending);
        assertEquals("FAILED", order.getStatus(), "取消后状态应为 FAILED");

        List<OrderLogPO> logs = orderLogMapper.selectByOrderId(aPending);
        assertEquals(1, logs.size(), "应写 1 条日志");
        OrderLogPO log = logs.get(0);
        assertEquals("CANCEL", log.getEvent(), "事件应为 CANCEL");
        assertEquals("PENDING", log.getFromStatus(), "from 应为 PENDING");
        assertEquals("FAILED", log.getToStatus(), "to 应为 FAILED");
    }

    // ============ 3. 重复创建：重复删除幂等 / 重复取消抛异常 ============

    @Test
    void 重复删除_幂等成功() {
        orderService.deleteByBuyer(aPending, BUYER_A);
        orderService.deleteByBuyer(aPending, BUYER_A);

        OrderPO order = selectById(aPending);
        assertEquals(1, order.getBuyerDeleted(), "重复删除后仍为 buyer_deleted=1");
        assertNotNull(order, "订单行仍在（逻辑删除，非物理删除）");
    }

    @Test
    void 重复取消_终态抛异常() {
        orderService.cancelByBuyer(aPending, BUYER_A);

        assertThrows(IllegalStateException.class,
                () -> orderService.cancelByBuyer(aPending, BUYER_A),
                "FAILED 终态重复取消应抛异常");
    }

    // ============ 4. 非法输入：他人/不存在/PAID 取消 ============

    @Test
    void 他人订单_详情抛异常() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.getBuyerOrderDetail(bPending, BUYER_A),
                "买家 A 读买家 B 订单应抛异常");
    }

    @Test
    void 不存在订单_详情抛异常() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.getBuyerOrderDetail(999999, BUYER_A),
                "不存在订单应抛异常");
    }

    @Test
    void 他人订单_删除零改动() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.deleteByBuyer(bPending, BUYER_A),
                "买家 A 删除买家 B 订单应抛异常");
        OrderPO order = selectById(bPending);
        assertEquals(0, order.getBuyerDeleted(), "他人订单不应被删除");
    }

    @Test
    void 他人订单_取消抛异常且零改动() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.cancelByBuyer(bPending, BUYER_A),
                "买家 A 取消买家 B 订单应抛异常");
        OrderPO order = selectById(bPending);
        assertEquals("PENDING", order.getStatus(), "他人订单状态不应改变");
    }

    @Test
    void PAID订单取消_抛IllegalStateException() {
        assertThrows(IllegalStateException.class,
                () -> orderService.cancelByBuyer(aPaid, BUYER_A),
                "已付款订单取消应抛 IllegalStateException（需走退款）");
        OrderPO order = selectById(aPaid);
        assertEquals("PAID", order.getStatus(), "已付款订单状态不应改变");
    }

    @Test
    void 删除后_订单标记为已删() {
        orderService.deleteByBuyer(aPending, BUYER_A);

        OrderPO order = selectById(aPending);
        assertNotNull(order, "订单行仍存在（后台保留）");
        assertEquals(1, order.getBuyerDeleted(), "buyer_deleted 应为 1");
    }
}
