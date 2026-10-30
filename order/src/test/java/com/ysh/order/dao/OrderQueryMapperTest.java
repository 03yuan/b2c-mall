package com.ysh.order.dao;

import com.ysh.order.dao.po.OrderPO;
import com.ysh.order.dao.po.OrderQueryPO;
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
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 商家端订单列表分页 + 多维筛选的真实 SQLite 测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 5.1（DAO 层 - OrderMapper + XML 动态 SQL）：
 * - 5.1.1 动态条件分页：shop_id 固定；order_no 精确；receiver_name/receiver_phone LIKE；
 *   order_type/order_category/status/pay_type/logistics_type/store_name 等值；
 *   create_time 起止区间含首尾（start 日 00:00:00 ≤ t ≤ end 日 23:59:59）
 *   （Fail 边界：LIKE 不带 % 变精确匹配；区间右边界写成 &lt; end → 尾日订单整天丢失）
 * - 5.1.2 售后状态：NONE=NOT EXISTS(tb_refund 任意行)；PROCESSING=EXISTS(status=PROCESSING)；
 *   DONE=EXISTS(status=DONE)（Fail 边界：JOIN 未去重 → 一单多条退款单时列表行重复）
 * - 5.1.3 状态快捷标签 = status 等值预设；「全部」不传 status（Fail 边界：残留默认状态条件 →
 *   交易失败/待成团订单永远看不到）
 * - 5.1.4 排序与分页边界：create_time DESC、同值 id DESC；page≥1、size 1~100；
 *   超页返回空；total=筛选命中总数（Fail 边界：按 id 排 → 下单时间排序失效）
 *
 * 用例分类（表格驱动，每个筛选用例双断言 selectPage 命中数与 countByFilter 一致）：
 * 1. 字段级校验：11 个筛选维度各自命中子集且不漏不串
 * 2. 类型与格式：精确 vs 模糊、大小写敏感、日期首尾边界、多条件组合
 * 3. 重复创建：同一筛选重复执行结果一致；翻页拼接无重复无遗漏；total 与条数一致
 * 4. 非法输入：shopId 为 null 不查全表；SQL 注入串/未知枚举值零命中；page/size 非法抛
 *    IllegalArgumentException 而非 SQL 500
 *
 * 测试库为 @TempDir 下的临时 SQLite 库；陷阱数据：店铺 2 的 1 单（同门店名、同状态、同日期）+ 一单两条退款单。
 */
class OrderQueryMapperTest {

    private static final int SHOP_A = 1;
    private static final int SHOP_B = 2;

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private OrderMapper orderMapper;

    // 店铺 A 的 10 单 + 店铺 B 的 1 单陷阱数据
    private int oPending;        // id1  PENDING       / 一号门店 / 10-20 10:00 / 张三 / 13800001111
    private int oPaidRefund;     // id2  PAID          / 一号门店 / 10-21 10:00 / 李四 / 退款 PROCESSING
    private int oShippedDone;    // id3  SHIPPED       / 二号门店 / 10-22 10:00 / 王五 / 退款 DONE
    private int oCompletedSameT; // id4  COMPLETED     / 二号门店 / 10-22 10:00（与 id3 同 create_time）
    private int oFailed;         // id5  FAILED        / 一号门店 / 10-23 10:00
    private int oGroupPending;   // id6  GROUP_PENDING / 一号门店 / 10-24 10:00
    private int oPendingDupRefund;// id7 PENDING       / 二号门店 / 10-25 10:00 / 张小三 / 两条 PROCESSING
    private int oPaidTailDay;    // id8  PAID          / 一号门店 / 10-26 23:59:59
    private int oShippedHeadDay; // id9  SHIPPED       / 一号门店 / 10-27 00:00:00
    private int oCompletedOld;   // id10 COMPLETED     / 三号门店 / 10-19 10:00
    private int oOtherShop;      // id11 PENDING       / 一号门店 / 10-22 10:00（店铺 B 陷阱）

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "order-query.db").getAbsolutePath();
        dataSource = new UnpooledDataSource("org.sqlite.JDBC", jdbcUrl, null, null);

        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        // 与 application.yml 的 map-underscore-to-camel-case 保持一致
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

        oPending = insertOrder(SHOP_A, "NO-A1", "PENDING", "NORMAL", "NORMAL", null,
                "张三", "13800001111", null, null, "一号门店", "2026-10-20 10:00:00", "buyer_a");
        oPaidRefund = insertOrder(SHOP_A, "NO-B2", "PAID", "SECKILL", "CARD", "WECHAT",
                "李四", "13800002222", null, null, "一号门店", "2026-10-21 10:00:00", "buyer_a");
        oShippedDone = insertOrder(SHOP_A, "NO-C3", "SHIPPED", "NORMAL", "NORMAL", "BALANCE",
                "王五", "13900003333", "EXPRESS", "SF001", "二号门店", "2026-10-22 10:00:00", "buyer_b");
        oCompletedSameT = insertOrder(SHOP_A, "NO-D4", "COMPLETED", "NORMAL", "NORMAL", "ALIPAY",
                "张三丰", "13700004444", "CITY", "CS001", "二号门店", "2026-10-22 10:00:00", "buyer_a");
        oFailed = insertOrder(SHOP_A, "NO-E5", "FAILED", "NORMAL", "NORMAL", null,
                "钱七", "13500005555", null, null, "一号门店", "2026-10-23 10:00:00", "buyer_a");
        oGroupPending = insertOrder(SHOP_A, "NO-F6", "GROUP_PENDING", "NORMAL", "NORMAL", null,
                "孙八", "13400006666", null, null, "一号门店", "2026-10-24 10:00:00", "buyer_a");
        oPendingDupRefund = insertOrder(SHOP_A, "NO-G7", "PENDING", "SECKILL", "NORMAL", "WECHAT",
                "张小三", "13812345678", null, null, "二号门店", "2026-10-25 10:00:00", "buyer_b");
        oPaidTailDay = insertOrder(SHOP_A, "NO-H8", "PAID", "NORMAL", "NORMAL", "WECHAT",
                "李四", "13800002222", null, null, "一号门店", "2026-10-26 23:59:59", "buyer_a");
        oShippedHeadDay = insertOrder(SHOP_A, "NO-I9", "SHIPPED", "NORMAL", "CARD", "BALANCE",
                "周九", "13300009999", "EXPRESS", "SF009", "一号门店", "2026-10-27 00:00:00", "buyer_a");
        oCompletedOld = insertOrder(SHOP_A, "NO-J10", "COMPLETED", "NORMAL", "NORMAL", "ALIPAY",
                "吴十", "13200000000", "CITY", "CS010", "三号门店", "2026-10-19 10:00:00", "buyer_b");
        oOtherShop = insertOrder(SHOP_B, "NO-X1", "PENDING", "NORMAL", "NORMAL", null,
                "外店买家", "13100001111", null, null, "一号门店", "2026-10-22 10:00:00", "buyer_a");

        insertRefund(oPaidRefund, SHOP_A, "PROCESSING");
        insertRefund(oShippedDone, SHOP_A, "DONE");
        // 一单两条退款单：验证 EXISTS 去重，列表不得出现重复行
        insertRefund(oPendingDupRefund, SHOP_A, "PROCESSING");
        insertRefund(oPendingDupRefund, SHOP_A, "PROCESSING");
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private int insertOrder(int shopId, String orderNo, String status, String orderType,
                            String orderCategory, String payType, String receiverName,
                            String receiverPhone, String logisticsType, String logisticsNo,
                            String storeName, String createTime, String buyerUsername) throws Exception {
        String sql = "INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                + " status, pay_type, order_type, order_category, logistics_type, logistics_no,"
                + " store_name, shop_name, sku_image, unit_price, is_gift, buyer_deleted,"
                + " receiver_name, receiver_phone, receiver_address, buyer_username, create_time)"
                + " VALUES (" + lit(orderNo) + ", " + shopId + ", 100, '测试商品', 2, 39.80, "
                + lit(status) + ", " + lit(payType) + ", " + lit(orderType) + ", " + lit(orderCategory)
                + ", " + lit(logisticsType) + ", " + lit(logisticsNo) + ", " + lit(storeName)
                + ", '测试店铺', 'https://img.example.com/a.png', 19.90, 0, 0, "
                + lit(receiverName) + ", " + lit(receiverPhone) + ", '上海市浦东新区测试路 1 号', "
                + lit(buyerUsername) + ", " + lit(createTime) + ")";
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
            try (ResultSet keys = st.getGeneratedKeys()) {
                assertTrue(keys.next(), "应返回自增主键");
                return keys.getInt(1);
            }
        }
    }

    private void insertRefund(int orderId, int shopId, String status) throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO tb_refund (order_id, shop_id, status, amount)"
                    + " VALUES (" + orderId + ", " + shopId + ", " + lit(status) + ", 39.80)");
        }
    }

    private static String lit(String value) {
        return value == null ? "NULL" : "'" + value + "'";
    }

    /** 登录店铺 A 的空白筛选条件 */
    private static OrderQueryPO shopAFilter() {
        OrderQueryPO filter = new OrderQueryPO();
        filter.setShopId(SHOP_A);
        return filter;
    }

    private static List<Integer> idsOf(List<OrderPO> orders) {
        List<Integer> ids = new ArrayList<>();
        for (OrderPO order : orders) {
            ids.add(order.getId());
        }
        return ids;
    }

    /** 双断言：countByFilter 总数 == selectPage 条数 == 期望条数，且命中集合与排序精确匹配 */
    private void assertFilter(OrderQueryPO filter, int expectedCount, Integer... expectedIds) {
        List<OrderPO> rows = orderMapper.selectPage(filter, 0, 100);
        int total = orderMapper.countByFilter(filter);

        assertEquals(expectedCount, total, "countByFilter 命中总数");
        assertEquals(expectedCount, rows.size(), "selectPage 命中条数应与 countByFilter 一致");
        assertEquals(Arrays.asList(expectedIds), idsOf(rows),
                "命中集合与排序应精确匹配（create_time DESC, id DESC）");
    }

    // ============ 1. 字段级校验：11 个筛选维度 ============

    /**
     * 列：用例名、筛选条件、期望总数、期望 id 顺序（create_time DESC, id DESC）
     */
    static Stream<Arguments> fieldFilterCases() {
        return Stream.of(
                arguments("无筛选返回本店全部10单", shopAFilter(), 10,
                        new Integer[]{9, 8, 7, 6, 5, 4, 3, 2, 1, 10}),
                arguments("订单编号精确命中", filter(f -> f.setOrderNo("NO-C3")), 1,
                        new Integer[]{3}),
                arguments("订单类型等值命中", filter(f -> f.setOrderType("SECKILL")), 2,
                        new Integer[]{7, 2}),
                arguments("订单类别等值命中", filter(f -> f.setOrderCategory("CARD")), 2,
                        new Integer[]{9, 2}),
                arguments("订单状态等值命中", filter(f -> f.setStatus("PENDING")), 2,
                        new Integer[]{7, 1}),
                arguments("收件人姓名模糊命中", filter(f -> f.setReceiverName("张")), 3,
                        new Integer[]{7, 4, 1}),
                arguments("联系电话模糊命中", filter(f -> f.setReceiverPhone("1380000")), 3,
                        new Integer[]{8, 2, 1}),
                arguments("支付方式等值命中", filter(f -> f.setPayType("WECHAT")), 3,
                        new Integer[]{8, 7, 2}),
                arguments("物流类型普通快递命中", filter(f -> f.setLogisticsType("EXPRESS")), 2,
                        new Integer[]{9, 3}),
                arguments("门店名称等值命中", filter(f -> f.setStoreName("一号门店")), 6,
                        new Integer[]{9, 8, 6, 5, 2, 1}),
                arguments("售后状态暂无售后命中", filter(f -> f.setRefundStatus("NONE")), 7,
                        new Integer[]{9, 8, 6, 5, 4, 1, 10}),
                arguments("售后状态退款申请中命中", filter(f -> f.setRefundStatus("PROCESSING")), 2,
                        new Integer[]{7, 2}),
                arguments("售后状态退款完成命中", filter(f -> f.setRefundStatus("DONE")), 1,
                        new Integer[]{3})
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fieldFilterCases")
    void 单维度筛选_命中集合与计数一致(String caseName, OrderQueryPO filter,
                                       int expectedCount, Integer[] expectedIds) {
        assertFilter(filter, expectedCount, expectedIds);
    }

    // ============ 2. 类型与格式：精确/模糊、大小写、日期边界、多条件组合 ============

    /**
     * 列：用例名、筛选条件、期望总数、期望 id 顺序（create_time DESC, id DESC）
     */
    static Stream<Arguments> formatFilterCases() {
        return Stream.of(
                arguments("订单编号部分匹配不命中（精确而非LIKE）",
                        filter(f -> f.setOrderNo("NO-A")), 0, new Integer[]{}),
                arguments("订单编号大小写敏感不命中",
                        filter(f -> f.setOrderNo("no-c3")), 0, new Integer[]{}),
                arguments("状态大小写敏感不命中",
                        filter(f -> f.setStatus("pending")), 0, new Integer[]{}),
                arguments("电话模糊不匹配则零命中",
                        filter(f -> f.setReceiverPhone("1390000")), 1, new Integer[]{3}),
                arguments("单日区间含当日00:00与23:59两单",
                        filter(f -> {
                            f.setStartDate("2026-10-22");
                            f.setEndDate("2026-10-22");
                        }), 2, new Integer[]{4, 3}),
                arguments("尾日23:59:59订单被纳入（不得写成小于end）",
                        filter(f -> {
                            f.setStartDate("2026-10-26");
                            f.setEndDate("2026-10-26");
                        }), 1, new Integer[]{8}),
                arguments("首日00:00:00订单被纳入",
                        filter(f -> {
                            f.setStartDate("2026-10-27");
                            f.setEndDate("2026-10-27");
                        }), 1, new Integer[]{9}),
                arguments("三日区间含首尾",
                        filter(f -> {
                            f.setStartDate("2026-10-22");
                            f.setEndDate("2026-10-24");
                        }), 4, new Integer[]{6, 5, 4, 3}),
                arguments("仅传开始日期按大于等于过滤",
                        filter(f -> f.setStartDate("2026-10-24")), 4,
                        new Integer[]{9, 8, 7, 6}),
                arguments("仅传结束日期按小于等于当日23:59:59过滤",
                        filter(f -> f.setEndDate("2026-10-21")), 3,
                        new Integer[]{2, 1, 10}),
                arguments("状态与门店组合",
                        filter(f -> {
                            f.setStatus("PAID");
                            f.setStoreName("一号门店");
                        }), 2, new Integer[]{8, 2}),
                arguments("类别与物流类型组合",
                        filter(f -> {
                            f.setOrderCategory("CARD");
                            f.setLogisticsType("EXPRESS");
                        }), 1, new Integer[]{9}),
                arguments("姓名模糊与状态组合",
                        filter(f -> {
                            f.setReceiverName("张");
                            f.setStatus("PENDING");
                        }), 2, new Integer[]{7, 1}),
                arguments("门店名与状态均无匹配返回空",
                        filter(f -> {
                            f.setStoreName("不存在门店");
                            f.setStatus("PAID");
                        }), 0, new Integer[]{})
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("formatFilterCases")
    void 格式与组合筛选_命中集合与计数一致(String caseName, OrderQueryPO filter,
                                           int expectedCount, Integer[] expectedIds) {
        assertFilter(filter, expectedCount, expectedIds);
    }

    /** 便捷构造：在店铺 A 条件下叠加单个筛选项 */
    static OrderQueryPO filter(java.util.function.Consumer<OrderQueryPO> customizer) {
        OrderQueryPO filter = shopAFilter();
        customizer.accept(filter);
        return filter;
    }

    // ============ 3. 重复创建：重复查询一致、翻页无重复无遗漏、total 同源 ============

    @Test
    void 同一筛选重复执行_结果完全一致() {
        OrderQueryPO filter = filter(f -> f.setStatus("PAID"));

        List<Integer> first = idsOf(orderMapper.selectPage(filter, 0, 100));
        List<Integer> second = idsOf(orderMapper.selectPage(filter, 0, 100));

        assertEquals(first, second, "同一筛选重复查询结果应一致");
        assertEquals(orderMapper.countByFilter(filter), first.size(), "count 与列表条数应一致");
    }

    @Test
    void 翻页拼接_无重复无遗漏且顺序稳定() {
        OrderQueryPO filter = shopAFilter();
        List<Integer> paged = new ArrayList<>();
        int pageSize = 3;
        for (int page = 0; page < 4; page++) {
            paged.addAll(idsOf(orderMapper.selectPage(filter, page * pageSize, pageSize)));
        }

        assertEquals(Arrays.asList(9, 8, 7, 6, 5, 4, 3, 2, 1, 10), paged,
                "四页拼接应等于全量倒序列表，无重复无遗漏");
        assertEquals(10, orderMapper.countByFilter(filter), "total 应为筛选命中总数而非本页条数");
    }

    @Test
    void 相同下单时间_按id倒序稳定排列() {
        List<Integer> ids = idsOf(orderMapper.selectPage(shopAFilter(), 0, 100));

        assertTrue(ids.indexOf(oCompletedSameT) < ids.indexOf(oShippedDone),
                "create_time 相同时应按 id DESC，id4 应排在 id3 之前");
    }

    @Test
    void 超过总页数_返回空列表() {
        OrderQueryPO filter = shopAFilter();

        assertTrue(orderMapper.selectPage(filter, 12, 3).isEmpty(), "offset 超出总数应返回空列表");
        assertEquals(Arrays.asList(10), idsOf(orderMapper.selectPage(filter, 9, 3)),
                "最后一页应返回剩余 1 条");
    }

    // ============ 4. 非法输入：null 店铺、注入串、未知枚举、非法分页参数 ============

    @Test
    void shopId为null_不得退化为全表查询() {
        OrderQueryPO filter = new OrderQueryPO();

        assertTrue(orderMapper.selectPage(filter, 0, 100).isEmpty(), "shopId 为 null 时不得返回任何订单");
        assertEquals(0, orderMapper.countByFilter(filter), "shopId 为 null 时计数应为 0");
    }

    @Test
    void shopId隔离_只看到本店订单() {
        OrderQueryPO filter = new OrderQueryPO();
        filter.setShopId(SHOP_B);

        assertFilter(filter, 1, oOtherShop);
    }

    /**
     * 列：用例名、筛选条件
     */
    static Stream<Arguments> illegalFilterCases() {
        return Stream.of(
                arguments("订单编号含SQL注入串零命中",
                        filter(f -> f.setOrderNo("' OR 1=1 --"))),
                arguments("收件人姓名含SQL注入串零命中",
                        filter(f -> f.setReceiverName("' OR '1'='1"))),
                arguments("未知订单状态零命中",
                        filter(f -> f.setStatus("UNKNOWN"))),
                arguments("未知售后状态零命中（不得回落为暂无售后）",
                        filter(f -> f.setRefundStatus("WHATEVER"))),
                arguments("未知支付方式零命中",
                        filter(f -> f.setPayType("CASH"))),
                arguments("空串订单编号零命中",
                        filter(f -> f.setOrderNo("")))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("illegalFilterCases")
    void 非法筛选值_安全零命中(String caseName, OrderQueryPO filter) {
        assertFilter(filter, 0);
    }

    /**
     * 列：用例名、page、size、是否合法、期望 offset
     */
    static Stream<Arguments> pageSizeCases() {
        return Stream.of(
                arguments("默认首页", 1, 10, true, 0),
                arguments("第2页每页10条", 2, 10, true, 10),
                arguments("第4页每页3条", 4, 3, true, 9),
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
        OrderQueryPO filter = new OrderQueryPO();
        filter.setPage(page);
        filter.setSize(size);

        if (valid) {
            assertEquals(expectedOffset, filter.resolveOffset(), caseName + "：offset 计算错误");
        } else {
            assertThrows(IllegalArgumentException.class, filter::resolveOffset,
                    caseName + "：应抛 IllegalArgumentException 而非 SQL 500");
        }
    }

    @Test
    void 单订单多条退款单_退款筛选不产生重复行() {
        OrderQueryPO processing = filter(f -> f.setRefundStatus("PROCESSING"));
        List<OrderPO> rows = orderMapper.selectPage(processing, 0, 100);

        assertEquals(2, rows.size(), "退款申请中应命中 2 单，不得因一单两条退款单而重复");
        assertEquals(2, idsOf(rows).stream().distinct().count(), "命中订单 id 应互不重复");
        assertTrue(idsOf(rows).contains(oPendingDupRefund), "含两条退款单的订单应仅出现一次");
    }
}
