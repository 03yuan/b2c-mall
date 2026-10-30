package com.ysh.order.service;

import com.ysh.order.dao.OrderLogMapper;
import com.ysh.order.dao.OrderMapper;
import com.ysh.order.dao.po.OrderPO;
import com.ysh.order.service.impl.OrderServiceImpl;
import com.ysh.order.statemachine.OrderStateFactory;
import com.ysh.order.web.request.BatchShipRequest;
import com.ysh.order.web.vo.BatchShipResult;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 批量发货（POST /order/batch-ship）的 Service 层真实 SQLite 测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 7.2：
 * - 7.2.1 请求体 items[{orderId, logisticsNo, logisticsType}] 逐条独立处理：成功逐条流转；
 *   非待发货/他店/不存在的订单记入失败列表（orderId+原因），好单照常发货不连坐
 *   （Fail 边界：一条失败整批回滚 -> 批量操作不可用）
 * - 7.2.2 入参校验：items 为空 -> 400（Controller）；单条缺物流单号 -> 该条计入失败、其余照常
 *   （Fail 边界：空 items 返回 200 成功 -> 前端误以为发货完成）
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：混合批次（正常 PAID / 已 SHIPPED / 他店）success=1、failed=2 且各带中文原因，DB 仅正常单变更
 * 2. 类型与格式：物流类型大小写/空格归一化后落库为大写
 * 3. 重复创建：同一订单在同批次出现两次 -> 第二次计入失败；已发货单重复发货 -> 失败
 * 4. 非法输入：缺物流单号/非法物流类型/PENDING/不存在订单 -> 各自计入失败且目标数据零改动
 *
 * 真实临时 SQLite 库 + 真实 MyBatis Mapper + 真实 OrderServiceImpl（stateFactory 为真实对象）。
 */
class OrderBatchShipServiceTest {

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
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "order-batch-ship.db").getAbsolutePath();
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

    private BatchShipRequest.Item item(Integer orderId, String logisticsNo, String logisticsType) {
        BatchShipRequest.Item it = new BatchShipRequest.Item();
        it.setOrderId(orderId);
        it.setLogisticsNo(logisticsNo);
        it.setLogisticsType(logisticsType);
        return it;
    }

    /** 从结果中按 orderId 取失败原因 */
    private String reasonOf(BatchShipResult result, int orderId) {
        for (BatchShipResult.FailedItem f : result.getFailed()) {
            if (orderId == f.getOrderId()) {
                return f.getReason();
            }
        }
        return null;
    }

    // ============ 1. 字段级校验：混合批次好单发货、失败单汇总且不连坐（7.2.1 主用例） ============

    @Test
    void 混合批次_好单发货失败单汇总且不连坐() throws Exception {
        int good = insertOrder(SHOP_A, "PAID");
        int dup = insertOrder(SHOP_A, "PAID");
        orderService.ship(dup, "SF-DUP", "EXPRESS", SHOP_A, OPERATOR); // 预置为已发货
        int other = insertOrder(SHOP_B, "PAID");

        BatchShipResult result = orderService.batchShip(
                Arrays.asList(item(good, "SF-GOOD", "EXPRESS"),
                        item(dup, "SF-DUP2", "CITY"),
                        item(other, "SF-OTHER", "EXPRESS")),
                SHOP_A, OPERATOR);

        assertEquals(1, result.getSuccess(), "仅 1 条好单发货成功");
        assertEquals(2, result.getFailed().size(), "已发货与他店订单应计入失败");

        // 好单落库：状态、物流单号、类型、日志
        assertEquals("SHIPPED", reload(good).getStatus(), "好单应流转 SHIPPED");
        assertEquals("SF-GOOD", reload(good).getLogisticsNo(), "好单物流单号应落库");
        assertEquals("EXPRESS", reload(good).getLogisticsType(), "好单物流类型应落库");
        assertEquals(1, readLogs(good).size(), "好单应写 1 条日志");

        // 重复单不再被覆盖
        assertEquals("SF-DUP", reload(dup).getLogisticsNo(), "已发货单物流单号不得被覆盖");
        assertEquals("EXPRESS", reload(dup).getLogisticsType(), "已发货单物流类型不得被覆盖");

        // 他店单原样
        assertEquals("PAID", reload(other).getStatus(), "他店订单状态不得改动");
        assertNull(reload(other).getLogisticsNo(), "他店订单物流单号不得写入");
        assertTrue(readLogs(other).isEmpty(), "他店订单不得写日志");

        // 失败原因（中文）
        assertTrue(reasonOf(result, dup).contains("不能重复发货"),
                "已发货单失败原因应含「不能重复发货」，实际：" + reasonOf(result, dup));
        assertTrue(reasonOf(result, other).contains("不存在"),
                "他店单失败原因应含「不存在」，实际：" + reasonOf(result, other));
    }

    // ============ 2. 类型与格式：物流类型归一化 ============

    @Test
    void 物流类型大小写与空格_归一化为大写落库() throws Exception {
        int orderId = insertOrder(SHOP_A, "PAID");

        BatchShipResult result = orderService.batchShip(
                Arrays.asList(item(orderId, "SF-UP", " express ")), SHOP_A, OPERATOR);

        assertEquals(1, result.getSuccess(), "应发货成功");
        assertEquals(0, result.getFailed().size(), "不应有失败项");
        assertEquals("EXPRESS", reload(orderId).getLogisticsType(), "物流类型应归一化为大写落库");
    }

    // ============ 3. 重复创建：同批次重复出现 / 已发货重复发货 ============

    @Test
    void 同批次同一订单出现两次_第二次计入失败() throws Exception {
        int orderId = insertOrder(SHOP_A, "PAID");

        BatchShipResult result = orderService.batchShip(
                Arrays.asList(item(orderId, "SF-1", "EXPRESS"), item(orderId, "SF-2", "CITY")),
                SHOP_A, OPERATOR);

        assertEquals(1, result.getSuccess(), "仅第一次发货成功");
        assertEquals(1, result.getFailed().size(), "第二次重复应计入失败");
        assertEquals("SF-1", reload(orderId).getLogisticsNo(), "第二次不得覆盖第一次物流单号");
        assertEquals("EXPRESS", reload(orderId).getLogisticsType(), "第二次不得覆盖第一次物流类型");
        assertEquals(1, readLogs(orderId).size(), "重复发货不得产生第二条日志");
    }

    // ============ 4. 非法输入：表驱动，各单条非法均计入失败且零改动 ============

    /**
     * 列：用例名、物流单号、物流类型、期望失败原因关键词
     */
    static Stream<Arguments> illegalItemCases() {
        return Stream.of(
                arguments("单号为null", null, "EXPRESS", "物流单号"),
                arguments("单号为空串", "", "EXPRESS", "物流单号"),
                arguments("单号为空白", "   ", "EXPRESS", "物流单号"),
                arguments("类型为null", "SF1", null, "物流类型"),
                arguments("类型为空串", "SF1", "", "物流类型"),
                arguments("类型不在枚举内", "SF1", "SAME_DAY", "物流类型")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("illegalItemCases")
    void 非法单条_计入失败且DB零改动(String caseName, String logisticsNo, String logisticsType,
                                 String expectedKeyword) throws Exception {
        int orderId = insertOrder(SHOP_A, "PAID");

        BatchShipResult result = orderService.batchShip(
                Arrays.asList(item(orderId, logisticsNo, logisticsType)), SHOP_A, OPERATOR);

        assertEquals(0, result.getSuccess(), caseName + "：不得成功");
        assertEquals(1, result.getFailed().size(), caseName + "：应计入失败");
        assertTrue(reasonOf(result, orderId).contains(expectedKeyword),
                caseName + "：失败原因应含「" + expectedKeyword + "」，实际：" + reasonOf(result, orderId));
        assertEquals("PAID", reload(orderId).getStatus(), caseName + "：状态不得改动");
        assertNull(reload(orderId).getLogisticsNo(), caseName + "：物流单号不得写入");
        assertTrue(readLogs(orderId).isEmpty(), caseName + "：不得写日志");
    }

    @Test
    void 缺物流单号的单条_计入失败其余好单照常() throws Exception {
        int bad = insertOrder(SHOP_A, "PAID");
        int good = insertOrder(SHOP_A, "PAID");

        BatchShipResult result = orderService.batchShip(
                Arrays.asList(item(bad, null, "EXPRESS"), item(good, "SF-OK", "EXPRESS")),
                SHOP_A, OPERATOR);

        assertEquals(1, result.getSuccess(), "好单应照常发货");
        assertEquals(1, result.getFailed().size(), "缺单号单应计入失败");
        assertTrue(reasonOf(result, bad).contains("物流单号"), "缺单号单原因应含「物流单号」");
        assertEquals("PAID", reload(bad).getStatus(), "缺单号单不得发货");
        assertEquals("SHIPPED", reload(good).getStatus(), "好单应发货成功");
    }

    @Test
    void 待付款订单_计入失败且零改动() throws Exception {
        int orderId = insertOrder(SHOP_A, "PENDING");

        BatchShipResult result = orderService.batchShip(
                Arrays.asList(item(orderId, "SF1", "EXPRESS")), SHOP_A, OPERATOR);

        assertEquals(0, result.getSuccess(), "待付款订单不得发货");
        assertEquals(1, result.getFailed().size(), "待付款订单应计入失败");
        assertTrue(reasonOf(result, orderId).contains("不能发货"),
                "待付款订单失败原因应含「不能发货」，实际：" + reasonOf(result, orderId));
        assertEquals("PENDING", reload(orderId).getStatus(), "状态不得改动");
        assertTrue(readLogs(orderId).isEmpty(), "不得写日志");
    }

    @Test
    void 不存在的订单_计入失败() throws Exception {
        BatchShipResult result = orderService.batchShip(
                Arrays.asList(item(999999, "SF1", "EXPRESS")), SHOP_A, OPERATOR);

        assertEquals(0, result.getSuccess(), "不存在订单不得成功");
        assertEquals(1, result.getFailed().size(), "不存在订单应计入失败");
        assertTrue(reasonOf(result, 999999).contains("不存在"),
                "不存在订单失败原因应含「不存在」，实际：" + reasonOf(result, 999999));
    }

    @Test
    void 他店订单_计入失败且目标数据原样() throws Exception {
        int otherShopOrderId = insertOrder(SHOP_B, "PAID");

        BatchShipResult result = orderService.batchShip(
                Arrays.asList(item(otherShopOrderId, "SF-HACK", "EXPRESS")), SHOP_A, OPERATOR);

        assertEquals(0, result.getSuccess(), "店铺 A 不得发货店铺 B 的订单");
        assertEquals(1, result.getFailed().size(), "他店订单应计入失败");
        assertTrue(reasonOf(result, otherShopOrderId).contains("不存在"),
                "他店订单失败原因应含「不存在」，实际：" + reasonOf(result, otherShopOrderId));
        assertEquals("PAID", reload(otherShopOrderId).getStatus(), "他店订单状态不得改动");
        assertNull(reload(otherShopOrderId).getLogisticsNo(), "他店订单物流单号不得写入");
        assertTrue(readLogs(otherShopOrderId).isEmpty(), "他店订单不得写日志");
    }
}
