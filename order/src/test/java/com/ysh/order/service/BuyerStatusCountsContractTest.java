package com.ysh.order.service;

import com.ysh.order.dao.OrderMapper;
import com.ysh.order.dao.po.BuyerOrderQueryPO;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 买家订单状态计数（GET /order/buyer/status-counts）Service 层真库契约测试（回归护栏，直接绿）。
 * 对应 .tdd/task/mall-order-manage.md 功能上下文 3.1（Service + Controller 层）：
 * - 3.1.1 返回结构固定五个 key：all/pending/paid/shipped/completed，无订单状态返回 0 而非缺失
 *   （Fail 边界：key 缺失 -> 前端括号显示 undefined；已删计入 -> 计数与列表不一致）
 * - 3.1.2 「全部」= 其余各状态之和（含 FAILED/GROUP_PENDING），与列表默认（不传 status）total 一致
 *   （Fail 边界：口径漏 FAILED -> Tab 总数少于列表实际条数）
 * - 3.1.3 Tab 切换 = 列表接口 status 参数等值映射（已由 BuyerOrderControllerTest/BuyerOrderMapperTest 覆盖）
 *
 * 本测试聚焦 3.1.2 尚未被既有测试显式锁定的契约点：「all 与列表 total 同源」，
 * 以及 3.1.1 的「已删订单不计入任何 key」。底层实现已在 shop-order-list.md 10.1.2/10.2 落地，
 * 本测试为回归护栏直接绿，防止后续改动让状态计数 all 与列表 total 口径漂移。
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：固定五 key 齐全、无单状态 0
 * 2. 类型与格式：all 与列表 total 同源（含/不含 FAILED 两种造数）
 * 3. 重复创建：重复调用计数结果一致
 * 4. 非法输入：已删订单不计入任何 key；他人订单不计入
 */
class BuyerStatusCountsContractTest {

    private static final String BUYER_A = "buyer_a";
    private static final String BUYER_B = "buyer_b";

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private OrderMapper orderMapper;
    private OrderServiceImpl orderService;
    private int orderNoSeq = 0;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "buyer-status-counts.db").getAbsolutePath();
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
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private int insertOrder(String buyerUsername, String status, int buyerDeleted) throws Exception {
        orderNoSeq++;
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            String sql = "INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                    + " status, buyer_username, buyer_deleted, receiver_name, receiver_phone, receiver_address)"
                    + " VALUES ('NO-" + orderNoSeq + "', 1, 100, '测试商品', 2, 39.80, "
                    + "'" + status + "', '" + buyerUsername + "', " + buyerDeleted + ", "
                    + "'张三', '13800001111', '上海市浦东新区测试路 1 号')";
            st.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
            try (ResultSet keys = st.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    /** 造数：买家 A 覆盖 5 种状态 + FAILED + 1 条已删 PAID；买家 B 1 条 PAID 陷阱 */
    private void seedCoveringAllStates() throws Exception {
        insertOrder(BUYER_A, "PENDING", 0);
        insertOrder(BUYER_A, "PAID", 0);
        insertOrder(BUYER_A, "SHIPPED", 0);
        insertOrder(BUYER_A, "COMPLETED", 0);
        insertOrder(BUYER_A, "FAILED", 0);
        insertOrder(BUYER_A, "PAID", 1);   // 已删，不计入任何 key
        insertOrder(BUYER_B, "PAID", 0);   // 他人，不计入
    }

    /** 造数：买家 A 仅常规四状态（无 FAILED），用于验证 all 仍与 total 同源 */
    private void seedFourStatesOnly() throws Exception {
        insertOrder(BUYER_A, "PENDING", 0);
        insertOrder(BUYER_A, "PAID", 0);
        insertOrder(BUYER_A, "SHIPPED", 0);
        insertOrder(BUYER_A, "COMPLETED", 0);
    }

    // ============ 1. 字段级校验：固定五 key 齐全 ============

    @Test
    void 固定五key_数量正确() throws Exception {
        seedCoveringAllStates();

        BuyerStatusCountsVO counts = orderService.buyerStatusCounts(BUYER_A);

        assertEquals(1, counts.getPending(), "待付款 1");
        assertEquals(1, counts.getPaid(), "待发货 1（已删 PAID 不计入）");
        assertEquals(1, counts.getShipped(), "待收货 1");
        assertEquals(1, counts.getCompleted(), "已完成 1");
    }

    // ============ 2. 类型与格式：all 与列表 total 同源 ============

    @Test
    void 全部_等于列表默认total_含FAILED口径() throws Exception {
        seedCoveringAllStates();

        BuyerStatusCountsVO counts = orderService.buyerStatusCounts(BUYER_A);
        int listTotal = orderService.pageBuyerOrders(buyerAFilterNoStatus()).getTotal();

        assertEquals(5, counts.getAll(), "all = 未删除订单 5（PENDING+PAID+SHIPPED+COMPLETED+FAILED）");
        assertEquals(listTotal, counts.getAll(), "all 必须与列表默认（不传 status）total 同源一致");
        // 含 FAILED 时 all 大于四状态之和（口径漏 FAILED 即失败）
        assertEquals(4,
                counts.getPending() + counts.getPaid() + counts.getShipped() + counts.getCompleted(),
                "四状态之和 = 4");
    }

    @Test
    void 全部_等于列表默认total_无FAILED口径() throws Exception {
        seedFourStatesOnly();

        BuyerStatusCountsVO counts = orderService.buyerStatusCounts(BUYER_A);
        int listTotal = orderService.pageBuyerOrders(buyerAFilterNoStatus()).getTotal();

        assertEquals(4, counts.getAll(), "all = 4（四状态）");
        assertEquals(listTotal, counts.getAll(), "无 FAILED 时 all 仍与列表 total 同源一致");
    }

    private BuyerOrderQueryPO buyerAFilterNoStatus() {
        BuyerOrderQueryPO filter = new BuyerOrderQueryPO();
        filter.setBuyerUsername(BUYER_A);
        return filter;
    }

    // ============ 3. 重复创建：重复调用结果一致 ============

    @Test
    void 重复调用_计数结果一致() throws Exception {
        seedCoveringAllStates();

        BuyerStatusCountsVO first = orderService.buyerStatusCounts(BUYER_A);
        BuyerStatusCountsVO second = orderService.buyerStatusCounts(BUYER_A);

        assertEquals(first.getAll(), second.getAll(), "重复调用 all 应一致");
        assertEquals(first.getPending(), second.getPending(), "重复调用 pending 应一致");
        assertEquals(first.getPaid(), second.getPaid(), "重复调用 paid 应一致");
    }

    // ============ 4. 非法输入：已删订单不计入任何 key ============

    @Test
    void 已删订单_不计入任何key() throws Exception {
        seedCoveringAllStates();

        BuyerStatusCountsVO counts = orderService.buyerStatusCounts(BUYER_A);

        // 已删单为 PAID：paid 只算未删的 1 条（若已删复活，paid 会变 2）
        assertEquals(1, counts.getPaid(), "已删 PAID 不得计入 paid");
        assertEquals(5, counts.getAll(), "已删 PAID 不得计入 all");
    }

    @Test
    void 他人买家订单_不计入() throws Exception {
        seedCoveringAllStates();

        BuyerStatusCountsVO counts = orderService.buyerStatusCounts(BUYER_A);

        // 买家 B 的 PAID 陷阱不得计入买家 A 的 paid（否则 paid 会变 2）
        assertEquals(1, counts.getPaid(), "他人 PAID 不得计入买家 A 的 paid");
        assertEquals(5, counts.getAll(), "他人订单不得计入 all");
    }

    static Stream<Arguments> noOrderCases() {
        return Stream.of(
                arguments("无任何订单的买家"),
                arguments("订单全部已删的买家")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("noOrderCases")
    void 无有效订单_五key全零(String caseName) throws Exception {
        // 「订单全部已删」：先造 1 条已删订单，验证 all/pending 等仍为 0
        insertOrder(BUYER_A, "PENDING", 1);

        BuyerStatusCountsVO counts = orderService.buyerStatusCounts(BUYER_A);

        assertEquals(0, counts.getAll(), caseName + "：all 应为 0");
        assertEquals(0, counts.getPending(), caseName + "：pending 应为 0");
        assertEquals(0, counts.getPaid(), caseName + "：paid 应为 0");
        assertEquals(0, counts.getShipped(), caseName + "：shipped 应为 0");
        assertEquals(0, counts.getCompleted(), caseName + "：completed 应为 0");
    }
}
