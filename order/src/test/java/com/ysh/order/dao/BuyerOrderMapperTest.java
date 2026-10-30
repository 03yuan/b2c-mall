package com.ysh.order.dao;

import com.ysh.order.dao.po.BuyerOrderQueryPO;
import com.ysh.order.dao.po.BuyerStatusCountPO;
import com.ysh.order.dao.po.OrderPO;
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

import java.io.File;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 买家端订单列表 + 状态计数的真实 SQLite 测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 10.1（DAO 层 - OrderMapper + XML）：
 * - 10.1.1 买家分页：buyer_username 恒置、buyer_deleted=0 恒置；status 可选等值；order_no 精确；
 *   排序 create_time DESC, id DESC；分页边界同商家端
 *   （Fail 边界：漏 buyer_deleted → 已删订单复活；漏 buyer_username → 看到他人订单）
 * - 10.1.2 状态计数：GROUP BY status 且排除 buyer_deleted=1；无单状态返回 0 而非缺失
 *   （Fail 边界：key 缺失 → 前端括号显示 undefined；已删订单计入 → 计数与列表不一致）
 *
 * 用例分类（表格驱动，每个筛选用例双断言 selectBuyerPage 命中数与 countBuyerByFilter 一致）：
 * 1. 字段级校验：status 等值 / order_no 精确各自命中且不漏不串
 * 2. 类型与格式：精确 vs 模糊、大小写、多条件组合、分页边界
 * 3. 重复创建：同一筛选重复执行结果一致；翻页拼接无重复无遗漏
 * 4. 非法输入：已删订单不出现；他人买家订单不出现；username 为 null 不查全表；非法分页参数抛异常
 *
 * 测试库为 @TempDir 下的临时 SQLite 库；陷阱数据：买家 B 的 2 单 + 买家 A 的 1 条已删订单。
 */
class BuyerOrderMapperTest {

    private static final String BUYER_A = "buyer_a";
    private static final String BUYER_B = "buyer_b";

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private OrderMapper orderMapper;

    // 买家 A 的 6 单（含 1 条已删）+ 买家 B 的 2 单陷阱
    private int aPaid;          // PAID          / 10-22 10:00
    private int aShipped;       // SHIPPED       / 10-23 10:00
    private int aCompleted;     // COMPLETED     / 10-24 10:00
    private int aPending;       // PENDING       / 10-25 10:00
    private int aFailed;        // FAILED        / 10-26 10:00
    private int aGroupPending;  // GROUP_PENDING / 10-27 10:00
    private int aDeleted;       // PAID（已删）  / 10-28 10:00
    private int bPaid;          // 买家 B PAID   / 10-22 10:00
    private int bShipped;       // 买家 B SHIPPED/ 10-23 10:00

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "buyer-order.db").getAbsolutePath();
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

        aPaid = insertOrder(BUYER_A, "NO-A1", "PAID", "2026-10-22 10:00:00", 0);
        aShipped = insertOrder(BUYER_A, "NO-A2", "SHIPPED", "2026-10-23 10:00:00", 0);
        aCompleted = insertOrder(BUYER_A, "NO-A3", "COMPLETED", "2026-10-24 10:00:00", 0);
        aPending = insertOrder(BUYER_A, "NO-A4", "PENDING", "2026-10-25 10:00:00", 0);
        aFailed = insertOrder(BUYER_A, "NO-A5", "FAILED", "2026-10-26 10:00:00", 0);
        aGroupPending = insertOrder(BUYER_A, "NO-A6", "GROUP_PENDING", "2026-10-27 10:00:00", 0);
        aDeleted = insertOrder(BUYER_A, "NO-A7", "PAID", "2026-10-28 10:00:00", 1);
        bPaid = insertOrder(BUYER_B, "NO-B1", "PAID", "2026-10-22 10:00:00", 0);
        bShipped = insertOrder(BUYER_B, "NO-B2", "SHIPPED", "2026-10-23 10:00:00", 0);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private int insertOrder(String buyerUsername, String orderNo, String status,
                            String createTime, int buyerDeleted) throws Exception {
        String sql = "INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                + " status, pay_type, buyer_username, buyer_deleted, receiver_name, receiver_phone,"
                + " receiver_address, shop_name, create_time)"
                + " VALUES ('" + orderNo + "', 1, 100, '测试商品', 2, 39.80, "
                + "'" + status + "', 'WECHAT', '" + buyerUsername + "', " + buyerDeleted + ", "
                + "'张三', '13800001111', '上海市浦东新区测试路 1 号', '测试店铺', '" + createTime + "')";
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
            try (ResultSet keys = st.getGeneratedKeys()) {
                assertTrue(keys.next(), "应返回自增主键");
                return keys.getInt(1);
            }
        }
    }

    private static BuyerOrderQueryPO buyerAFilter() {
        BuyerOrderQueryPO filter = new BuyerOrderQueryPO();
        filter.setBuyerUsername(BUYER_A);
        return filter;
    }

    private static List<Integer> idsOf(List<OrderPO> orders) {
        List<Integer> ids = new ArrayList<>();
        for (OrderPO order : orders) {
            ids.add(order.getId());
        }
        return ids;
    }

    private void assertBuyerFilter(BuyerOrderQueryPO filter, int expectedCount, Integer... expectedIds) {
        List<OrderPO> rows = orderMapper.selectBuyerPage(filter, 0, 100);
        int total = orderMapper.countBuyerByFilter(filter);

        assertEquals(expectedCount, total, "countBuyerByFilter 命中总数");
        assertEquals(expectedCount, rows.size(), "selectBuyerPage 命中条数应与 countBuyerByFilter 一致");
        assertEquals(Arrays.asList(expectedIds), idsOf(rows),
                "命中集合与排序应精确匹配（create_time DESC, id DESC）");
    }

    // ============ 1. 字段级校验：status 等值 / order_no 精确 ============

    static Stream<Arguments> fieldCases() {
        return Stream.of(
                arguments("无筛选返回买家A全部未删6单", buyerAFilter(), 6,
                        new Integer[]{6, 5, 4, 3, 2, 1}),
                arguments("状态待付款PENDING", filter(f -> f.setStatus("PENDING")), 1,
                        new Integer[]{4}),
                arguments("状态待发货PAID", filter(f -> f.setStatus("PAID")), 1,
                        new Integer[]{1}),
                arguments("状态待收货SHIPPED", filter(f -> f.setStatus("SHIPPED")), 1,
                        new Integer[]{2}),
                arguments("状态已完成COMPLETED", filter(f -> f.setStatus("COMPLETED")), 1,
                        new Integer[]{3}),
                arguments("状态交易失败FAILED", filter(f -> f.setStatus("FAILED")), 1,
                        new Integer[]{5}),
                arguments("状态待成团GROUP_PENDING", filter(f -> f.setStatus("GROUP_PENDING")), 1,
                        new Integer[]{6}),
                arguments("订单编号精确命中", filter(f -> f.setOrderNo("NO-A2")), 1,
                        new Integer[]{2})
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fieldCases")
    void 买家单维度筛选_命中集合与计数一致(String caseName, BuyerOrderQueryPO filter,
                                          int expectedCount, Integer[] expectedIds) {
        assertBuyerFilter(filter, expectedCount, expectedIds);
    }

    // ============ 2. 类型与格式：精确 vs 模糊、大小写、组合、分页边界 ============

    static Stream<Arguments> formatCases() {
        return Stream.of(
                arguments("订单编号部分匹配不命中（精确而非LIKE）",
                        filter(f -> f.setOrderNo("NO-A")), 0, new Integer[]{}),
                arguments("订单编号大小写敏感不命中",
                        filter(f -> f.setOrderNo("no-a2")), 0, new Integer[]{}),
                arguments("状态大小写敏感不命中",
                        filter(f -> f.setStatus("paid")), 0, new Integer[]{}),
                arguments("状态与订单编号组合命中",
                        filter(f -> {
                            f.setStatus("PAID");
                            f.setOrderNo("NO-A1");
                        }), 1, new Integer[]{1}),
                arguments("状态与订单编号组合不匹配（订单编号属于他人）",
                        filter(f -> {
                            f.setStatus("PAID");
                            f.setOrderNo("NO-B1");
                        }), 0, new Integer[]{}),
                arguments("未知状态零命中",
                        filter(f -> f.setStatus("UNKNOWN")), 0, new Integer[]{})
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("formatCases")
    void 买家格式与组合筛选_命中集合与计数一致(String caseName, BuyerOrderQueryPO filter,
                                              int expectedCount, Integer[] expectedIds) {
        assertBuyerFilter(filter, expectedCount, expectedIds);
    }

    static BuyerOrderQueryPO filter(java.util.function.Consumer<BuyerOrderQueryPO> customizer) {
        BuyerOrderQueryPO filter = buyerAFilter();
        customizer.accept(filter);
        return filter;
    }

    // ============ 3. 重复创建：重复查询一致、翻页拼接无重复无遗漏 ============

    @Test
    void 同一筛选重复执行_结果完全一致() {
        BuyerOrderQueryPO filter = filter(f -> f.setStatus("PAID"));

        List<Integer> first = idsOf(orderMapper.selectBuyerPage(filter, 0, 100));
        List<Integer> second = idsOf(orderMapper.selectBuyerPage(filter, 0, 100));

        assertEquals(first, second, "同一筛选重复查询结果应一致");
        assertEquals(orderMapper.countBuyerByFilter(filter), first.size(), "count 与列表条数应一致");
    }

    @Test
    void 翻页拼接_无重复无遗漏且顺序稳定() {
        BuyerOrderQueryPO filter = buyerAFilter();
        List<Integer> paged = new ArrayList<>();
        int pageSize = 2;
        for (int page = 0; page < 3; page++) {
            paged.addAll(idsOf(orderMapper.selectBuyerPage(filter, page * pageSize, pageSize)));
        }

        assertEquals(Arrays.asList(6, 5, 4, 3, 2, 1), paged,
                "三页拼接应等于全量倒序列表，无重复无遗漏");
        assertEquals(6, orderMapper.countBuyerByFilter(filter), "total 应为筛选命中总数而非本页条数");
    }

    @Test
    void 超过总页数_返回空列表() {
        BuyerOrderQueryPO filter = buyerAFilter();

        assertTrue(orderMapper.selectBuyerPage(filter, 20, 2).isEmpty(), "offset 超出总数应返回空列表");
    }

    // ============ 4. 非法输入：已删订单 / 他人订单 / null username / 非法分页 ============

    @Test
    void 已删订单_任何筛选下不出现() {
        BuyerOrderQueryPO filter = buyerAFilter();

        List<Integer> all = idsOf(orderMapper.selectBuyerPage(filter, 0, 100));
        assertTrue(!all.contains(aDeleted), "已删除订单（buyer_deleted=1）不得出现在列表");

        // 已删订单为 PAID 状态，状态筛选 PAID 也不得复活
        List<Integer> paid = idsOf(orderMapper.selectBuyerPage(filter(f -> f.setStatus("PAID")), 0, 100));
        assertTrue(!paid.contains(aDeleted), "状态筛选 PAID 时已删订单仍不得复活");
        assertEquals(Arrays.asList(aPaid), paid, "PAID 应仅命中未删除的 1 条");
    }

    @Test
    void 他人买家订单_不出现() {
        BuyerOrderQueryPO filter = buyerAFilter();

        List<Integer> all = idsOf(orderMapper.selectBuyerPage(filter, 0, 100));
        assertTrue(!all.contains(bPaid) && !all.contains(bShipped), "买家 A 不得看到买家 B 的订单");
    }

    @Test
    void 买家B查询_只看到自己订单() {
        BuyerOrderQueryPO filter = new BuyerOrderQueryPO();
        filter.setBuyerUsername(BUYER_B);

        assertBuyerFilter(filter, 2, bShipped, bPaid);
    }

    @Test
    void username为null_不得退化为全表查询() {
        BuyerOrderQueryPO filter = new BuyerOrderQueryPO();

        assertTrue(orderMapper.selectBuyerPage(filter, 0, 100).isEmpty(), "username 为 null 时不得返回任何订单");
        assertEquals(0, orderMapper.countBuyerByFilter(filter), "username 为 null 时计数应为 0");
    }

    static Stream<Arguments> pageSizeCases() {
        return Stream.of(
                arguments("默认首页", 1, 10, true, 0),
                arguments("第2页每页2条", 2, 2, true, 2),
                arguments("第4页每页2条", 4, 2, true, 6),
                arguments("每页100条上限", 1, 100, true, 0),
                arguments("页码为0非法", 0, 10, false, 0),
                arguments("页码为负非法", -1, 10, false, 0),
                arguments("每页0条非法", 1, 0, false, 0),
                arguments("每页负数非法", 1, -5, false, 0),
                arguments("每页101条超上限非法", 1, 101, false, 0)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pageSizeCases")
    void 分页参数校验_非法抛IllegalArgumentException(String caseName, int page, int size,
                                                    boolean valid, int expectedOffset) {
        BuyerOrderQueryPO filter = new BuyerOrderQueryPO();
        filter.setPage(page);
        filter.setSize(size);

        if (valid) {
            assertEquals(expectedOffset, filter.resolveOffset(), caseName + "：offset 计算错误");
        } else {
            assertThrows(IllegalArgumentException.class, filter::resolveOffset,
                    caseName + "：应抛 IllegalArgumentException 而非 SQL 500");
        }
    }

    // ============ 状态计数（10.1.2）：GROUP BY status 且排除 buyer_deleted=1 ============

    private Map<String, Integer> statusCountMap(String buyerUsername) {
        Map<String, Integer> map = new HashMap<>();
        List<BuyerStatusCountPO> rows = orderMapper.countBuyerStatuses(buyerUsername);
        if (rows != null) {
            for (BuyerStatusCountPO row : rows) {
                map.put(row.getStatus(), row.getCount());
            }
        }
        return map;
    }

    @Test
    void 状态计数_各状态数量正确且已删订单不计入() {
        Map<String, Integer> counts = statusCountMap(BUYER_A);

        assertEquals(1, counts.get("PAID"), "PAID 应 1 条（已删的 PAID 不计入）");
        assertEquals(1, counts.get("SHIPPED"), "SHIPPED 应 1 条");
        assertEquals(1, counts.get("COMPLETED"), "COMPLETED 应 1 条");
        assertEquals(1, counts.get("PENDING"), "PENDING 应 1 条");
        assertEquals(1, counts.get("FAILED"), "FAILED 应 1 条");
        assertEquals(1, counts.get("GROUP_PENDING"), "GROUP_PENDING 应 1 条");
        assertEquals(6, counts.values().stream().mapToInt(Integer::intValue).sum(),
                "各状态计数之和应等于未删除订单总数 6");
    }

    @Test
    void 状态计数_他人买家不计入() {
        Map<String, Integer> counts = statusCountMap(BUYER_A);

        assertEquals(1, counts.get("PAID"), "买家 B 的 PAID 订单不得计入买家 A 的计数");
        assertEquals(1, counts.get("SHIPPED"), "买家 B 的 SHIPPED 订单不得计入买家 A 的计数");
    }

    @Test
    void 状态计数_无单买家返回空聚合() {
        Map<String, Integer> counts = statusCountMap("nobody");

        assertTrue(counts.isEmpty(), "无任何订单的买家应返回空聚合（Service 层归一化为 0）");
    }
}
