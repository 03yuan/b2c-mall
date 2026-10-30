package com.ysh.order.service;

import com.ysh.order.dao.OrderMapper;
import com.ysh.order.dao.po.OrderPO;
import com.ysh.order.service.impl.OrderServiceImpl;
import com.ysh.order.web.vo.BuyerStatusCountsVO;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 买家收藏/取消收藏的 Service 真库测试（TDD 红阶段）。
 * 对应 .tdd/task/mall-order-manage.md 功能上下文 6.2（Controller + Service 层 - 收藏/取消收藏接口）：
 * - 6.2.1 favByBuyer：本人且未删 -> fav=1；他人/不存在/已删 -> 404（IllegalArgumentException 含"不存在"）
 * - 6.2.2 unfavByBuyer：同上契约 fav 置 0，幂等
 * - 6.2.3 收藏不改变状态机、不写 tb_order_log、不影响状态计数（纯标记字段）
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：收藏本人 -> fav=1
 * 2. 类型与格式：取消收藏 -> fav=0
 * 3. 重复创建：重复取消幂等
 * 4. 非法输入：他人/不存在/已删 -> 抛"订单不存在"；6.2.3 状态不变 + 计数不变
 */
class BuyerFavServiceTest {

    private static final String BUYER_A = "buyer_a";
    private static final String BUYER_B = "buyer_b";

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private OrderMapper orderMapper;
    private OrderServiceImpl orderService;
    private int orderNoSeq = 0;

    private int aPending;     // 买家 A 未删未收藏 PENDING
    private int aDeleted;     // 买家 A 已删
    private int bPending;     // 买家 B 未删

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "buyer-fav-service.db").getAbsolutePath();
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

        aPending = insertOrder(BUYER_A, 0);
        aDeleted = insertOrder(BUYER_A, 1);
        bPending = insertOrder(BUYER_B, 0);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private int insertOrder(String buyerUsername, int buyerDeleted) throws Exception {
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
            ps.setString(7, "PENDING");
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

    // ============ 1. 字段级校验：收藏本人 -> fav=1 ============

    @Test
    void 收藏本人订单_fav为1() {
        orderService.favByBuyer(aPending, BUYER_A);

        assertEquals(1, reload(aPending).getBuyerFav(), "收藏后 buyer_fav 应为 1");
    }

    // ============ 2. 类型与格式：取消收藏 -> fav=0 ============

    @Test
    void 取消收藏本人订单_fav为0() {
        orderService.favByBuyer(aPending, BUYER_A);
        orderService.unfavByBuyer(aPending, BUYER_A);

        assertEquals(0, reload(aPending).getBuyerFav(), "取消收藏后 buyer_fav 应为 0");
    }

    // ============ 3. 重复创建：重复取消幂等 ============

    @Test
    void 重复取消_状态幂等fav仍0() {
        orderService.favByBuyer(aPending, BUYER_A);
        orderService.unfavByBuyer(aPending, BUYER_A);
        orderService.unfavByBuyer(aPending, BUYER_A);

        assertEquals(0, reload(aPending).getBuyerFav(), "重复取消后 buyer_fav 仍为 0");
    }

    // ============ 4. 非法输入：他人/不存在/已删 404 + 6.2.3 状态/计数不变 ============

    @Test
    void 收藏他人订单_抛订单不存在() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.favByBuyer(bPending, BUYER_A),
                "收藏他人订单应抛订单不存在");
        assertEquals(0, reload(bPending).getBuyerFav(), "他人订单 buyer_fav 不得被改动");
    }

    @Test
    void 收藏不存在订单_抛订单不存在() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.favByBuyer(99999, BUYER_A),
                "收藏不存在订单应抛订单不存在");
    }

    @Test
    void 收藏已删订单_抛订单不存在() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.favByBuyer(aDeleted, BUYER_A),
                "收藏已删订单应抛订单不存在");
    }

    // ============ 6.2.3：收藏不改变状态机、不影响状态计数 ============

    @Test
    void 收藏不改变订单状态() {
        orderService.favByBuyer(aPending, BUYER_A);

        assertEquals("PENDING", reload(aPending).getStatus(), "收藏后 status 应仍为 PENDING（不触发状态机）");
    }

    @Test
    void 收藏不影响状态计数() {
        BuyerStatusCountsVO before = orderService.buyerStatusCounts(BUYER_A);

        orderService.favByBuyer(aPending, BUYER_A);

        BuyerStatusCountsVO after = orderService.buyerStatusCounts(BUYER_A);
        assertEquals(before.getAll(), after.getAll(), "收藏不应影响 all 计数");
        assertEquals(before.getPending(), after.getPending(), "收藏不应影响 pending 计数");
    }
}
