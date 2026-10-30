package com.ysh.order.service;

import com.ysh.order.dao.OrderLogMapper;
import com.ysh.order.dao.OrderMapper;
import com.ysh.order.dao.RefundMapper;
import com.ysh.order.dao.po.OrderPO;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * 买家逻辑删除订单（deleteByBuyer）契约测试（TDD 回归保障，直接绿）。
 * 对应 .tdd/task/mall-order-manage.md 功能上下文 11.1（删除订单【已实现·回归】）：
 * - 11.1.1 逻辑删除 buyer_deleted=1，后台数据保留；删除后列表/计数/详情/一切买家操作接口均不可见不可操作；
 *          重复删除幂等 200；未收藏收藏标记等其它字段保留原值；商家端列表仍可见（履约数据不丢）
 * - 11.1.2 他人订单删除 -> 404 零改动
 *
 * 契约锁定点：softDeleteByBuyer 只改 buyer_deleted + update_time，其它字段（buyer_fav 收藏标记等）原值保留，
 * 且删除后详情/取消/收藏/支付/确认收货等一切买家操作接口均因 selectByIdAndBuyerUsername 带 buyer_deleted=0 而 404。
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：删除后 buyer_deleted=1 且 buyer_fav/status/pay_type/total_price 原值保留
 * 2. 类型与格式：行仍在（物理不删，商家端履约数据不丢）
 * 3. 重复删除：幂等
 * 4. 非法输入：他人删除 404 零改动；删除后各操作接口 404 不可操作
 */
class BuyerDeleteContractTest {

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
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "buyer-delete-contract.db").getAbsolutePath();
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
        }

        session = sqlSessionFactory.openSession(true);
        orderMapper = session.getMapper(OrderMapper.class);
        orderLogMapper = mock(OrderLogMapper.class);
        refundMapper = mock(RefundMapper.class);

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

    private int insertOrder(String buyerUsername, int buyerFav, String status) throws Exception {
        orderNoSeq++;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                             + " status, pay_type, receiver_name, receiver_phone, receiver_address,"
                             + " buyer_username, buyer_deleted, buyer_fav)"
                             + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "NO-" + orderNoSeq);
            ps.setInt(2, 1);
            ps.setInt(3, 100);
            ps.setString(4, "测试商品");
            ps.setInt(5, 2);
            ps.setBigDecimal(6, new java.math.BigDecimal("39.80"));
            ps.setString(7, status);
            ps.setString(8, "WECHAT");
            ps.setString(9, "张三");
            ps.setString(10, "13800001111");
            ps.setString(11, "上海市浦东新区测试路 1 号");
            ps.setString(12, buyerUsername);
            ps.setInt(13, 0);
            ps.setInt(14, buyerFav);
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

    // ============ 1. 字段级校验：删除后 buyer_deleted=1 且其它字段原值保留 ============

    @Test
    void 删除后_buyerFav等其它字段保留原值() throws Exception {
        int id = insertOrder(BUYER_A, 1, "PENDING");

        orderService.deleteByBuyer(id, BUYER_A);

        OrderPO order = reload(id);
        assertEquals(1, order.getBuyerDeleted(), "删除后 buyer_deleted 应为 1");
        assertEquals(1, order.getBuyerFav(), "收藏标记 buyer_fav 应保留原值（逻辑删除不改其它字段）");
        assertEquals("PENDING", order.getStatus(), "status 应保留原值");
        assertEquals("WECHAT", order.getPayType(), "pay_type 应保留原值");
        assertEquals(0, order.getTotalPrice().compareTo(new java.math.BigDecimal("39.80")),
                "total_price 应保留原值（BigDecimal 按值比较，忽略 scale）");
    }

    // ============ 2. 类型与格式：行仍在（物理不删，商家端履约数据不丢） ============

    @Test
    void 删除后_订单行仍在物理不删() throws Exception {
        int id = insertOrder(BUYER_A, 0, "PENDING");

        orderService.deleteByBuyer(id, BUYER_A);

        assertNotNull(reload(id), "逻辑删除后订单行必须仍在（商家端履约数据不丢）");
    }

    // ============ 3. 重复删除：幂等 ============

    @Test
    void 重复删除_幂等成功() throws Exception {
        int id = insertOrder(BUYER_A, 0, "PENDING");

        orderService.deleteByBuyer(id, BUYER_A);
        orderService.deleteByBuyer(id, BUYER_A);

        assertEquals(1, reload(id).getBuyerDeleted(), "重复删除后仍为 buyer_deleted=1");
    }

    // ============ 4. 非法输入：他人删除 404 零改动；删除后操作接口 404 不可操作 ============

    @Test
    void 他人订单删除_抛订单不存在且零改动() throws Exception {
        int otherId = insertOrder(BUYER_B, 0, "PENDING");

        assertThrows(IllegalArgumentException.class,
                () -> orderService.deleteByBuyer(otherId, BUYER_A),
                "删除他人订单应抛订单不存在");

        assertEquals(0, reload(otherId).getBuyerDeleted(), "他人订单 buyer_deleted 不得被改动");
    }

    @Test
    void 删除后_详情不可见() throws Exception {
        int id = insertOrder(BUYER_A, 0, "PENDING");
        orderService.deleteByBuyer(id, BUYER_A);

        assertThrows(IllegalArgumentException.class,
                () -> orderService.getBuyerOrderDetail(id, BUYER_A),
                "删除后详情应不可见（404）");
    }

    @Test
    void 删除后_取消不可操作() throws Exception {
        int id = insertOrder(BUYER_A, 0, "PENDING");
        orderService.deleteByBuyer(id, BUYER_A);

        assertThrows(IllegalArgumentException.class,
                () -> orderService.cancelByBuyer(id, BUYER_A),
                "删除后取消应不可操作（404）");
    }

    @Test
    void 删除后_收藏不可操作() throws Exception {
        int id = insertOrder(BUYER_A, 0, "PENDING");
        orderService.deleteByBuyer(id, BUYER_A);

        assertThrows(IllegalArgumentException.class,
                () -> orderService.favByBuyer(id, BUYER_A),
                "删除后收藏应不可操作（404）");
    }
}
