package com.ysh.order.service;

import com.ysh.order.dao.OrderMapper;
import com.ysh.order.dao.RefundMapper;
import com.ysh.order.dao.po.OrderQueryPO;
import com.ysh.order.service.impl.OrderServiceImpl;
import com.ysh.order.web.vo.OrderRowVO;
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
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 导出订单数据组装的 Service 层真实 SQLite 测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 8.1（导出与列表共用同一筛选构建器）：
 * - 8.1.1 按当前筛选条件取全量（不分页），排序与列表一致（create_time DESC, id DESC），不含条件外订单
 *   （Fail 边界：忽略筛选导出全店数据；被默认 page size 截断导致导出不全）
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：超过默认页大小（size=10）的数据仍全量返回，且行 VO 复用列表组装（字段齐全）
 * 2. 类型与格式：排序 create_time DESC, id DESC（同时间按 id DESC）；筛选条件复用（status 等值命中）
 * 3. 重复创建：同一筛选重复导出结果一致（幂等，无副作用）
 * 4. 非法输入：他店订单不泄漏（shopId 隔离）；shopId 为 null 不得退化全表导出
 *
 * 真实临时 SQLite 库 + 真实 MyBatis Mapper + 真实 OrderServiceImpl（复用列表筛选与行组装）。
 * 陷阱数据：店铺 B 的 1 单（与店铺 A 同状态 PAID）。
 */
class OrderExportServiceTest {

    private static final int SHOP_A = 1;
    private static final int SHOP_B = 2;

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private OrderMapper orderMapper;
    private RefundMapper refundMapper;
    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "order-export.db").getAbsolutePath();
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
                    + "pay_time DATETIME,"
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

        // 店铺 A 12 单（超过默认 page size=10）：id 1~4 PAID / 5~8 SHIPPED / 9~12 COMPLETED
        // id 11、12 同 create_time（10-11），用于验证同时间按 id DESC
        for (int i = 1; i <= 12; i++) {
            String createTime = i <= 10
                    ? String.format("2026-10-%02d 10:00:00", i)
                    : "2026-10-11 10:00:00";
            insertOrder(SHOP_A, "NO-" + i, statusOf(i), createTime);
        }
        // 店铺 B 陷阱单（同状态 PAID，验证 shopId 隔离不泄漏）
        insertOrder(SHOP_B, "NO-13", "PAID", "2026-10-20 10:00:00");
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    /** 造数据状态分布：id 1~4 PAID、5~8 SHIPPED、9~12 COMPLETED */
    private static String statusOf(int id) {
        if (id <= 4) {
            return "PAID";
        }
        if (id <= 8) {
            return "SHIPPED";
        }
        return "COMPLETED";
    }

    private int insertOrder(int shopId, String orderNo, String status, String createTime) throws Exception {
        String sql = "INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                + " status, pay_type, order_type, order_category, logistics_type, logistics_no,"
                + " store_name, shop_name, sku_image, unit_price, is_gift, buyer_deleted,"
                + " receiver_name, receiver_phone, receiver_address, buyer_username, create_time)"
                + " VALUES (" + lit(orderNo) + ", " + shopId + ", 100, '测试商品', 2, 39.80, "
                + lit(status) + ", " + lit(null) + ", 'NORMAL', 'NORMAL', "
                + lit(null) + ", " + lit(null) + ", '一号门店', '测试店铺', "
                + "'https://img.example.com/a.png', 19.90, 0, 0, "
                + "'张三', '13800005678', '上海市浦东新区测试路 1 号', 'buyer_a', " + lit(createTime) + ")";
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
            try (ResultSet keys = st.getGeneratedKeys()) {
                assertTrue(keys.next(), "应返回自增主键");
                return keys.getInt(1);
            }
        }
    }

    private static String lit(String value) {
        return value == null ? "NULL" : "'" + value + "'";
    }

    private static OrderQueryPO shopAFilter() {
        OrderQueryPO filter = new OrderQueryPO();
        filter.setShopId(SHOP_A);
        return filter;
    }

    private static List<Integer> idsOf(List<OrderRowVO> rows) {
        List<Integer> ids = new ArrayList<>();
        for (OrderRowVO row : rows) {
            ids.add(row.getOrderId());
        }
        return ids;
    }

    // ============ 1. 字段级校验：全量导出 + 行 VO 复用列表组装 ============

    @Test
    void 全量导出_超过默认页大小仍返回全部() {
        List<OrderRowVO> rows = orderService.exportOrders(shopAFilter());

        assertEquals(12, rows.size(), "导出应返回本店全部 12 单，不得按默认 size=10 截断");
    }

    @Test
    void 行VO复用列表组装_字段齐全() {
        List<OrderRowVO> rows = orderService.exportOrders(shopAFilter());
        OrderRowVO first = rows.get(0);

        assertEquals("COMPLETED", first.getStatus(), "首行为最新订单（id 12），状态 COMPLETED");
        assertEquals("NO-12", first.getOrderNo(), "orderNo 应正确");
        assertNotNull(first.getItems(), "items 应为数组");
        assertEquals(1, first.getItems().size(), "一单一商品模型下 items 恒 1 条");
        assertEquals("138****5678", first.getReceiverPhoneMasked(), "导出复用列表行组装（列表口径脱敏）");
    }

    // ============ 2. 类型与格式：排序 create_time DESC, id DESC ============

    @Test
    void 排序_下单时间倒序且同时间按id倒序() {
        List<OrderRowVO> rows = orderService.exportOrders(shopAFilter());

        assertEquals(Arrays.asList(12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1), idsOf(rows),
                "排序应为 create_time DESC, id DESC（id 12、11 同时间，12 在前）");
    }

    /**
     * 列：用例名、筛选状态、期望命中条数（复用列表筛选构建器，导出条数 = 条件命中总数）
     */
    static Stream<Arguments> statusFilterCases() {
        return Stream.of(
                arguments("待发货PAID", "PAID", 4),
                arguments("待收货SHIPPED", "SHIPPED", 4),
                arguments("交易成功COMPLETED", "COMPLETED", 4)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("statusFilterCases")
    void 筛选条件复用_状态等值命中(String caseName, String status, int expectedCount) {
        OrderQueryPO filter = shopAFilter();
        filter.setStatus(status);

        List<OrderRowVO> rows = orderService.exportOrders(filter);

        assertEquals(expectedCount, rows.size(), caseName + "：导出条数应等于该条件命中总数");
    }

    // ============ 3. 重复创建：同一筛选重复导出结果一致 ============

    @Test
    void 同一筛选重复导出_结果一致() {
        List<Integer> first = idsOf(orderService.exportOrders(shopAFilter()));
        List<Integer> second = idsOf(orderService.exportOrders(shopAFilter()));

        assertEquals(first, second, "同一筛选重复导出，订单集合与顺序应完全一致");
    }

    // ============ 4. 非法输入：shopId 隔离 / null 不退化全表 ============

    @Test
    void shopId隔离_他店订单不导出() {
        List<OrderRowVO> rows = orderService.exportOrders(shopAFilter());

        assertEquals(12, rows.size(), "店铺 A 导出应仅含本店订单");
        assertFalse(idsOf(rows).contains(13), "店铺 B 的订单（id 13）不得混入店铺 A 的导出");
    }

    @Test
    void shopId为null_不得退化全表导出() {
        OrderQueryPO filter = new OrderQueryPO();

        List<OrderRowVO> rows = orderService.exportOrders(filter);

        assertTrue(rows.isEmpty(), "shopId 为 null 时不得导出任何订单");
    }
}
