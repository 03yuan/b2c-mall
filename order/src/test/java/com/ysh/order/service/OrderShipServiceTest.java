package com.ysh.order.service;

import com.ysh.order.dao.OrderLogMapper;
import com.ysh.order.dao.OrderMapper;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 发货（携带物流信息）的 Service 层真实 SQLite 测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 7.1：
 * - 7.1.1 ship(orderId, logisticsNo, logisticsType, shopId, operatorId)：仅 PAID 可发货（状态机保障），
 *   成功时写入 logistics_no/logistics_type、状态流转 SHIPPED、写 tb_order_log（event=SHIP）
 *   （Fail 边界：状态流转了但物流单号不落库 -> 详情页无物流信息）
 * - 7.1.2 参数校验：logisticsNo 非空、logisticsType 在枚举内（EXPRESS/CITY）-> 违反 400；
 *   shopId 强制隔离（他店订单不生效）
 *   （Fail 边界：空单号发货成功 -> 商家无单号履约；信任前端 shopId -> 越权发货）
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：PAID 发货 -> SHIPPED、物流单号与类型落库、日志 from/to/event/operator 正确
 * 2. 类型与格式：两种物流类型均可发货；物流类型大小写/空格归一化后落库为大写
 * 3. 重复创建：重复发货 -> 抛异常、物流信息与日志均不改变（不产生第二条日志）
 * 4. 非法输入：空/空白单号、非法物流类型、PENDING 发货、他店订单、不存在订单 -> 各自报错且目标数据零改动
 *
 * 真实临时 SQLite 库 + 真实 MyBatis Mapper + 真实 OrderServiceImpl（stateFactory 为真实对象）。
 */
class OrderShipServiceTest {

    private static final Integer SHOP_A = 1;
    private static final Integer SHOP_B = 2;
    private static final Integer OPERATOR = 9;

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private OrderMapper orderMapper;
    private OrderLogMapper orderLogMapper;
    private OrderServiceImpl orderService;
    private int orderNoSeq = 0;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "order-ship.db").getAbsolutePath();
        dataSource = new UnpooledDataSource("org.sqlite.JDBC", jdbcUrl, null, null);

        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        for (String resource : Arrays.asList("mapper/OrderMapper.xml", "mapper/OrderLogMapper.xml")) {
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
                    + "shop_name VARCHAR(100),"
                    + "sku_image VARCHAR(500),"
                    + "unit_price DECIMAL(10,2),"
                    + "logistics_no VARCHAR(64),"
                    + "logistics_type VARCHAR(20),"
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
        }

        session = sqlSessionFactory.openSession(true);
        orderMapper = session.getMapper(OrderMapper.class);
        orderLogMapper = session.getMapper(OrderLogMapper.class);

        orderService = new OrderServiceImpl();
        ReflectionTestUtils.setField(orderService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(orderService, "orderLogMapper", orderLogMapper);
        ReflectionTestUtils.setField(orderService, "stateFactory", new OrderStateFactory());
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private int insertOrder(int shopId, String status) throws Exception {
        orderNoSeq++;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                             + " status, receiver_name, receiver_phone, receiver_address)"
                             + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "NO-" + orderNoSeq);
            ps.setInt(2, shopId);
            ps.setInt(3, 1);
            ps.setString(4, "测试商品");
            ps.setInt(5, 2);
            ps.setBigDecimal(6, new BigDecimal("39.80"));
            ps.setString(7, status);
            ps.setString(8, "张三");
            ps.setString(9, "13800005678");
            ps.setString(10, "上海市浦东新区");
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

    /** 读回订单日志（按 id 升序） */
    private List<String[]> readLogs(int orderId) throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT from_status, to_status, event, operator_id FROM tb_order_log"
                             + " WHERE order_id = ? ORDER BY id ASC")) {
            ps.setInt(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3),
                            String.valueOf(rs.getObject(4))});
                }
            }
        }
        return rows;
    }

    // ============ 1. 字段级校验：物流落库、状态流转、日志正确 ============

    @Test
    void PAID发货_物流落库且状态流转SHIPPED并写日志() throws Exception {
        int orderId = insertOrder(SHOP_A, "PAID");

        orderService.ship(orderId, "SF1234567890", "EXPRESS", SHOP_A, OPERATOR);

        OrderPO shipped = reload(orderId);
        assertEquals("SHIPPED", shipped.getStatus(), "发货后状态应为 SHIPPED");
        assertEquals("SF1234567890", shipped.getLogisticsNo(), "物流单号必须落库");
        assertEquals("EXPRESS", shipped.getLogisticsType(), "物流类型必须落库");

        List<String[]> logs = readLogs(orderId);
        assertEquals(1, logs.size(), "发货应写 1 条状态流转日志");
        assertEquals("PAID", logs.get(0)[0], "日志 from_status");
        assertEquals("SHIPPED", logs.get(0)[1], "日志 to_status");
        assertEquals("SHIP", logs.get(0)[2], "日志 event 应为 SHIP");
        assertEquals(String.valueOf(OPERATOR), logs.get(0)[3], "日志 operator_id");
    }

    // ============ 2. 类型与格式：两种物流类型 + 大小写归一化 ============

    /**
     * 列：用例名、传入物流类型、期望落库的物流类型
     */
    static Stream<Arguments> logisticsTypeCases() {
        return Stream.of(
                arguments("普通快递EXPRESS", "EXPRESS", "EXPRESS"),
                arguments("同城配送CITY", "CITY", "CITY"),
                arguments("小写express归一化", "express", "EXPRESS"),
                arguments("前后空格CITY归一化", " CITY ", "CITY")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("logisticsTypeCases")
    void 物流类型_合法值发货成功(String caseName, String inputType, String expectedType) throws Exception {
        int orderId = insertOrder(SHOP_A, "PAID");

        orderService.ship(orderId, "SF0001", inputType, SHOP_A, OPERATOR);

        OrderPO shipped = reload(orderId);
        assertEquals("SHIPPED", shipped.getStatus(), caseName + "：应发货成功");
        assertEquals(expectedType, shipped.getLogisticsType(), caseName + "：物流类型应归一化为大写落库");
    }

    // ============ 3. 重复创建：重复发货抛异常且不产生第二条日志 ============

    @Test
    void 重复发货_抛异常且物流与日志不改变() throws Exception {
        int orderId = insertOrder(SHOP_A, "PAID");
        orderService.ship(orderId, "SF-FIRST", "EXPRESS", SHOP_A, OPERATOR);

        assertThrows(IllegalStateException.class,
                () -> orderService.ship(orderId, "SF-SECOND", "CITY", SHOP_A, OPERATOR),
                "已发货订单重复发货应抛 IllegalStateException");

        OrderPO order = reload(orderId);
        assertEquals("SHIPPED", order.getStatus(), "状态不得改变");
        assertEquals("SF-FIRST", order.getLogisticsNo(), "物流单号不得被第二次覆盖");
        assertEquals("EXPRESS", order.getLogisticsType(), "物流类型不得被第二次覆盖");
        assertEquals(1, readLogs(orderId).size(), "重复发货不得产生第二条日志");
    }

    // ============ 4. 非法输入：参数与状态与归属全部零改动 ============

    /**
     * 列：用例名、物流单号、物流类型、期望异常类型
     */
    static Stream<Arguments> invalidArgumentCases() {
        return Stream.of(
                arguments("单号为null", null, "EXPRESS", IllegalArgumentException.class),
                arguments("单号为空串", "", "EXPRESS", IllegalArgumentException.class),
                arguments("单号为空白", "   ", "EXPRESS", IllegalArgumentException.class),
                arguments("物流类型为null", "SF1", null, IllegalArgumentException.class),
                arguments("物流类型为空串", "SF1", "", IllegalArgumentException.class),
                arguments("物流类型不在枚举内", "SF1", "SAME_DAY", IllegalArgumentException.class)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidArgumentCases")
    void 非法参数_报错且DB零改动(String caseName, String logisticsNo, String logisticsType,
                            Class<? extends Exception> expected) throws Exception {
        int orderId = insertOrder(SHOP_A, "PAID");

        assertThrows(expected,
                () -> orderService.ship(orderId, logisticsNo, logisticsType, SHOP_A, OPERATOR),
                caseName + "：应抛出 " + expected.getSimpleName());

        OrderPO order = reload(orderId);
        assertEquals("PAID", order.getStatus(), caseName + "：状态不得改动");
        assertNull(order.getLogisticsNo(), caseName + "：物流单号不得写入");
        assertNull(order.getLogisticsType(), caseName + "：物流类型不得写入");
        assertTrue(readLogs(orderId).isEmpty(), caseName + "：不得写日志");
    }

    @Test
    void 待付款发货_抛状态异常且零改动() throws Exception {
        int orderId = insertOrder(SHOP_A, "PENDING");

        assertThrows(IllegalStateException.class,
                () -> orderService.ship(orderId, "SF1", "EXPRESS", SHOP_A, OPERATOR),
                "PENDING 订单不可发货");

        OrderPO order = reload(orderId);
        assertEquals("PENDING", order.getStatus(), "状态不得改动");
        assertNull(order.getLogisticsNo(), "物流单号不得写入");
        assertTrue(readLogs(orderId).isEmpty(), "不得写日志");
    }

    @Test
    void 他店订单发货_不生效且目标数据原样() throws Exception {
        int otherShopOrderId = insertOrder(SHOP_B, "PAID");

        assertThrows(IllegalArgumentException.class,
                () -> orderService.ship(otherShopOrderId, "SF-HACK", "EXPRESS", SHOP_A, OPERATOR),
                "店铺 A 不得发货店铺 B 的订单");

        OrderPO order = reload(otherShopOrderId);
        assertEquals("PAID", order.getStatus(), "他店订单状态不得改动");
        assertNull(order.getLogisticsNo(), "他店订单物流单号不得写入");
        assertTrue(readLogs(otherShopOrderId).isEmpty(), "他店订单不得写日志");
    }

    @Test
    void 订单不存在_抛参数异常() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.ship(999999, "SF1", "EXPRESS", SHOP_A, OPERATOR),
                "不存在的订单应抛 IllegalArgumentException");
    }

    @Test
    void shopId为null_不得发货() throws Exception {
        int orderId = insertOrder(SHOP_A, "PAID");

        assertThrows(IllegalArgumentException.class,
                () -> orderService.ship(orderId, "SF1", "EXPRESS", null, OPERATOR),
                "shopId 为空时不得退化为跨店发货");

        assertEquals("PAID", reload(orderId).getStatus(), "状态不得改动");
        assertTrue(readLogs(orderId).isEmpty(), "不得写日志");
    }
}
