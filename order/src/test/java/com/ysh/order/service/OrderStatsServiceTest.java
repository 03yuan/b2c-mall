package com.ysh.order.service;

import com.ysh.order.dao.OrderMapper;
import com.ysh.order.dao.po.MetricsPO;
import com.ysh.order.service.impl.OrderStatsServiceImpl;
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
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 按小时聚合支付金额（Service + DAO）的真实 SQLite 测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-dashboard.md 功能上下文 3.1：
 * - 3.1.1 按小时聚合支付金额：统计口径=按支付时间归属小时，仅统计已支付订单
 *   （PAID/SHIPPED/COMPLETED）
 *   （Fail 边界：按创建时间而非支付时间聚合；或把未支付订单金额计入）
 * - 3.1.2 缺失小时补 0（曲线连续，序列长度 24），全天无支付则整个区块为 null
 *   （Fail 边界：序列长度不为 24 时前端 X 轴错位）
 *
 * 功能上下文 4.1（order-service 指标计算）测试在同一测试类后半部分：
 * - 4.1.1 支付客户数（今日 distinct 买家）、支付订单数
 *   （Fail 边界：客户数不去重；按天边界错位）
 * - 4.1.2 退款金额（今日退款总金额）、客单价=今日总支付金额÷支付订单数
 *   （Fail 边界：分母含未支付单；0 单除零须返回 null 而非 Infinity）
 * tb_order 新增 buyer_username、tb_refund 新增 amount（绿阶段需同步写入 order.sql）。
 *
 * 用例分类（表格驱动，适配按小时聚合行为）：
 * 1. 字段级校验：各小时金额归位、同小时多笔求和；SHIPPED/COMPLETED 计入已支付
 * 2. 类型与格式：序列长度 24、缺失小时补 0、边界小时 0/23 归位
 * 3. 重复创建：同小时多笔金额累加；重复查询幂等（查询接口无创建语义）
 * 4. 非法输入：未支付不计入、按支付时间而非创建时间归属、日界外不计入、
 *    无支付时间不计入、跨店隔离；全天无支付 -> null（暂无数据）
 *
 * 测试库为 @TempDir 下的临时 SQLite 库（绝对路径连接串）。
 * tb_order 需新增 pay_time 字段（按支付时间归属小时），绿阶段需同步写入 order.sql。
 */
class OrderStatsServiceTest {

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private OrderStatsService orderStatsService;
    private int orderNoSeq = 0;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "order-test.db").getAbsolutePath();
        dataSource = new UnpooledDataSource("org.sqlite.JDBC", jdbcUrl, null, null);

        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        try (InputStream in = Resources.getResourceAsStream("mapper/OrderMapper.xml")) {
            new XMLMapperBuilder(in, configuration, "mapper/OrderMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);

        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS tb_order");
            st.executeUpdate("DROP TABLE IF EXISTS tb_refund");
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
                    + "buyer_username VARCHAR(50),"
                    + "receiver_name VARCHAR(50) NOT NULL,"
                    + "receiver_phone VARCHAR(20) NOT NULL,"
                    + "receiver_address VARCHAR(500) NOT NULL,"
                    + "create_time DATETIME DEFAULT (datetime('now','localtime')),"
                    + "update_time DATETIME DEFAULT (datetime('now','localtime')))");
            st.executeUpdate("CREATE TABLE tb_refund ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "order_id INTEGER NOT NULL,"
                    + "shop_id INTEGER NOT NULL,"
                    + "status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',"
                    + "amount DECIMAL(10,2) NOT NULL,"
                    + "create_time DATETIME DEFAULT (datetime('now','localtime')))");
        }

        session = sqlSessionFactory.openSession(true);
        orderStatsService = new OrderStatsServiceImpl(session.getMapper(OrderMapper.class));
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    /** payTime 为 "-" 表示无支付时间（SQL NULL） */
    private void insertOrder(int shopId, String status, String createTime, String payTime,
                             String amount) throws Exception {
        insertOrder(shopId, status, createTime, payTime, amount, "buyer-default");
    }

    private void insertOrder(int shopId, String status, String createTime, String payTime,
                             String amount, String buyerUsername) throws Exception {
        orderNoSeq++;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                             + " status, pay_time, buyer_username, receiver_name, receiver_phone,"
                             + " receiver_address, create_time)"
                             + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, "NO-" + orderNoSeq);
            ps.setInt(2, shopId);
            ps.setInt(3, 1);
            ps.setString(4, "测试商品");
            ps.setInt(5, 1);
            ps.setBigDecimal(6, new BigDecimal(amount));
            ps.setString(7, status);
            ps.setString(8, "-".equals(payTime) ? null : payTime);
            ps.setString(9, buyerUsername);
            ps.setString(10, "张三");
            ps.setString(11, "13800000000");
            ps.setString(12, "测试地址");
            ps.setString(13, createTime);
            ps.executeUpdate();
        }
    }

    private void insertRefund(int shopId, String createTime, String amount) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_refund (order_id, shop_id, status, amount, create_time)"
                             + " VALUES (?,?,?,?,?)")) {
            ps.setInt(1, 1);
            ps.setInt(2, shopId);
            ps.setString(3, "PROCESSING");
            ps.setBigDecimal(4, new BigDecimal(amount));
            ps.setString(5, createTime);
            ps.executeUpdate();
        }
    }

    /**
     * 列：用例名、店铺 1 插入的订单列表（状态|创建时间|支付时间|金额，逗号分隔，支付时间 "-"=无）、
     *     查询的自然日、期望序列（"小时=金额" 逗号分隔；"null"=期望整段为 null）
     */
    static Stream<Arguments> hourlyCases() {
        return Stream.of(
                // ----1. 字段级校验：各小时金额归位、同小时求和、已支付口径----
                arguments("8点与13点各归位",
                        "PAID|2026-10-22 08:00:00|2026-10-22 08:30:00|10.50,"
                                + "PAID|2026-10-22 12:00:00|2026-10-22 13:05:00|20.00",
                        "2026-10-22", "8=10.50,13=20.00"),
                arguments("同小时多笔求和",
                        "PAID|2026-10-22 08:00:00|2026-10-22 08:10:00|10.50,"
                                + "PAID|2026-10-22 08:30:00|2026-10-22 08:50:00|4.25",
                        "2026-10-22", "8=14.75"),
                arguments("SHIPPED与COMPLETED计入已支付",
                        "SHIPPED|2026-10-22 09:00:00|2026-10-22 10:20:00|10.50,"
                                + "COMPLETED|2026-10-22 10:00:00|2026-10-22 11:40:00|4.25",
                        "2026-10-22", "10=10.50,11=4.25"),

                // ----2. 类型与格式：序列长度 24、缺失小时补 0、边界小时归位----
                arguments("0点与23点边界归位其余补0",
                        "PAID|2026-10-22 00:00:00|2026-10-22 00:00:00|5.00,"
                                + "PAID|2026-10-22 23:30:00|2026-10-22 23:59:59|7.00",
                        "2026-10-22", "0=5.00,23=7.00"),

                // ----3. 重复创建（查询接口无创建语义 -> 同小时多笔累加见上；重复查询幂等见下方独立测试）----

                // ----4. 非法输入：未支付/无支付时间不计入、按支付时间归属、日界外、全天无支付----
                arguments("未支付订单不计入",
                        "PENDING|2026-10-22 08:00:00|2026-10-22 08:30:00|10.50",
                        "2026-10-22", "null"),
                arguments("已支付与未支付混排只计已支付",
                        "PENDING|2026-10-22 08:00:00|2026-10-22 08:30:00|10.50,"
                                + "PAID|2026-10-22 08:00:00|2026-10-22 09:30:00|20.00",
                        "2026-10-22", "9=20.00"),
                arguments("按支付时间归属而非创建时间",
                        "PAID|2026-10-21 09:00:00|2026-10-22 09:30:00|10.50",
                        "2026-10-22", "9=10.50"),
                arguments("支付时间在昨日不计入今日",
                        "PAID|2026-10-22 08:00:00|2026-10-21 23:59:59|10.50",
                        "2026-10-22", "null"),
                arguments("支付时间在次日不计入",
                        "PAID|2026-10-22 08:00:00|2026-10-23 00:00:00|10.50",
                        "2026-10-22", "null"),
                arguments("无支付时间不计入",
                        "PAID|2026-10-22 08:00:00|-|10.50",
                        "2026-10-22", "null"),
                arguments("全天无支付返回null暂无数据",
                        "",
                        "2026-10-22", "null")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hourlyCases")
    void hourlyPaidAmounts(String caseName, String orderSpec, String queryDay,
                           String expectedSpec) throws Exception {
        for (String entry : orderSpec.split(",")) {
            if (!entry.isEmpty()) {
                String[] f = entry.split("\\|");
                insertOrder(1, f[0], f[1], f[2], f[3]);
            }
        }

        List<BigDecimal> amounts = orderStatsService.hourlyPaidAmounts(1, LocalDate.parse(queryDay));

        if ("null".equals(expectedSpec)) {
            assertNull(amounts, "全天无支付应返回 null（前端暂无数据）");
            return;
        }
        assertNotNull(amounts, "有支付的自然日应返回 24 点序列");
        assertEquals(24, amounts.size(), "序列必须 24 个点，前端 X 轴才不错位");
        boolean[] checked = new boolean[24];
        for (String pair : expectedSpec.split(",")) {
            String[] kv = pair.split("=");
            int hour = Integer.parseInt(kv[0]);
            assertEquals(0, new BigDecimal(kv[1]).compareTo(amounts.get(hour)),
                    "第 " + hour + " 点金额错误");
            checked[hour] = true;
        }
        for (int h = 0; h < 24; h++) {
            if (!checked[h]) {
                assertEquals(0, BigDecimal.ZERO.compareTo(amounts.get(h)),
                        "第 " + h + " 点缺失小时应补 0");
            }
        }
    }

    // ============ 3. 重复创建（查询接口无创建语义 -> 重复查询幂等、无副作用） ============

    @Test
    void hourlyPaidAmounts_重复查询_结果一致且无副作用() throws Exception {
        insertOrder(1, "PAID", "2026-10-22 08:00:00", "2026-10-22 08:30:00", "10.50");

        List<BigDecimal> first = orderStatsService.hourlyPaidAmounts(1, LocalDate.parse("2026-10-22"));
        List<BigDecimal> second = orderStatsService.hourlyPaidAmounts(1, LocalDate.parse("2026-10-22"));

        assertNotNull(first);
        assertEquals(24, first.size());
        assertEquals(first, second, "重复查询结果应完全一致且不产生额外数据");
    }

    // ============ 4. 非法输入：跨店隔离 ============

    @Test
    void hourlyPaidAmounts_跨店隔离_只计自己的() throws Exception {
        insertOrder(1, "PAID", "2026-10-22 08:00:00", "2026-10-22 08:30:00", "10.50");
        insertOrder(2, "PAID", "2026-10-22 08:00:00", "2026-10-22 08:30:00", "99.00");

        List<BigDecimal> shop1 = orderStatsService.hourlyPaidAmounts(1, LocalDate.parse("2026-10-22"));
        List<BigDecimal> shop2 = orderStatsService.hourlyPaidAmounts(2, LocalDate.parse("2026-10-22"));

        assertEquals(0, new BigDecimal("10.50").compareTo(shop1.get(8)), "店铺 1 应只统计自己的 10.50");
        assertEquals(0, new BigDecimal("99.00").compareTo(shop2.get(8)), "店铺 2 应只统计自己的 99.00");
    }

    // ============ 功能上下文 4.1：指标计算（支付客户数/支付订单数/退款金额/客单价） ============

    /**
     * 列：用例名、店铺 1 订单列表（状态|创建时间|支付时间|金额|买家，逗号分隔）、
     *     店铺 1 退款列表（创建时间|金额，逗号分隔，"-"=无）、
     *     期望指标（"支付客户数,支付订单数,退款金额,客单价"，客单价 "null"=期望暂无数据）
     */
    static Stream<Arguments> metricsCases() {
        return Stream.of(
                // ----1. 字段级校验：客户数按买家去重、订单数、退款金额、客单价----
                arguments("2客户3单客户数去重",
                        "PAID|2026-10-22 08:00:00|2026-10-22 08:30:00|100.00|buyerA,"
                                + "PAID|2026-10-22 09:00:00|2026-10-22 09:30:00|50.00|buyerA,"
                                + "PAID|2026-10-22 10:00:00|2026-10-22 10:30:00|50.00|buyerB",
                        "-", "2,3,0,66.67"),
                arguments("支付200元2单退款50客单价100",
                        "PAID|2026-10-22 08:00:00|2026-10-22 08:30:00|100.00|buyerA,"
                                + "PAID|2026-10-22 09:00:00|2026-10-22 09:30:00|100.00|buyerB",
                        "2026-10-22 15:00:00|50.00", "2,2,50,100.00"),
                arguments("SHIPPED与COMPLETED计入已支付",
                        "SHIPPED|2026-10-22 07:00:00|2026-10-22 08:30:00|100.00|buyerA,"
                                + "COMPLETED|2026-10-22 08:00:00|2026-10-22 09:30:00|50.00|buyerA",
                        "-", "1,2,0,75.00"),

                // ----2. 类型与格式：客单价=总支付金额÷支付订单数，四舍五入保留两位（66.67）----
                //      （与「2客户3单」行共用断言，见 runner 的 compareTo）

                // ----3. 重复创建（查询接口无创建语义 -> 重复查询幂等）见下方独立测试----

                // ----4. 非法输入：未支付不计入、按支付时间归属、日界外、除零、退款只计今日----
                arguments("未支付单不计入指标",
                        "PENDING|2026-10-22 08:00:00|2026-10-22 08:30:00|100.00|buyerA",
                        "-", "0,0,0,null"),
                arguments("0单时客单价null不抛除零",
                        "", "-", "0,0,0,null"),
                arguments("按支付时间归属而非创建时间",
                        "PAID|2026-10-21 08:00:00|2026-10-22 08:30:00|100.00|buyerA",
                        "-", "1,1,0,100.00"),
                arguments("支付时间在昨日不计入今日",
                        "PAID|2026-10-22 08:00:00|2026-10-21 23:59:59|100.00|buyerA",
                        "-", "0,0,0,null"),
                arguments("退款多笔求和且只计今日",
                        "",
                        "2026-10-22 10:00:00|30.00,2026-10-22 11:00:00|20.00,2026-10-21 10:00:00|99.00",
                        "0,0,50,null")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("metricsCases")
    void dailyMetrics(String caseName, String orderSpec, String refundSpec,
                      String expectedSpec) throws Exception {
        for (String entry : orderSpec.split(",")) {
            if (!entry.isEmpty()) {
                String[] f = entry.split("\\|");
                insertOrder(1, f[0], f[1], f[2], f[3], f[4]);
            }
        }
        if (!"-".equals(refundSpec)) {
            for (String entry : refundSpec.split(",")) {
                String[] f = entry.split("\\|");
                insertRefund(1, f[0], f[1]);
            }
        }

        MetricsPO metrics = orderStatsService.dailyMetrics(1, LocalDate.parse("2026-10-22"));

        assertNotNull(metrics, "指标计算不应返回 null");
        String[] expected = expectedSpec.split(",", -1);
        assertEquals(Integer.parseInt(expected[0]), metrics.getPayCustomers(),
                "支付客户数错误（需按买家去重）");
        assertEquals(Integer.parseInt(expected[1]), metrics.getPayOrders(),
                "支付订单数错误（只计已支付订单）");
        assertEquals(0, new BigDecimal(expected[2]).compareTo(metrics.getRefundAmount()),
                "退款金额错误（今日退款总金额）");
        if ("null".equals(expected[3])) {
            assertNull(metrics.getAvgOrderValue(),
                    "0 单时客单价应为 null（暂无数据），不得抛除零/返回 Infinity");
        } else {
            assertEquals(0, new BigDecimal(expected[3]).compareTo(metrics.getAvgOrderValue()),
                    "客单价=今日总支付金额÷支付订单数");
        }
    }

    // ----3. 重复创建（查询接口无创建语义 -> 重复查询幂等、无副作用）----

    @Test
    void dailyMetrics_重复查询_结果一致且无副作用() throws Exception {
        insertOrder(1, "PAID", "2026-10-22 08:00:00", "2026-10-22 08:30:00", "100.00", "buyerA");
        insertRefund(1, "2026-10-22 15:00:00", "50.00");

        MetricsPO first = orderStatsService.dailyMetrics(1, LocalDate.parse("2026-10-22"));
        MetricsPO second = orderStatsService.dailyMetrics(1, LocalDate.parse("2026-10-22"));

        assertNotNull(first);
        assertEquals(first, second, "重复查询结果应完全一致且不产生额外数据");
    }

    // ----4. 非法输入：跨店隔离----

    @Test
    void dailyMetrics_跨店隔离_只计自己的() throws Exception {
        insertOrder(1, "PAID", "2026-10-22 08:00:00", "2026-10-22 08:30:00", "100.00", "buyerA");
        insertRefund(1, "2026-10-22 15:00:00", "50.00");
        insertOrder(2, "PAID", "2026-10-22 08:00:00", "2026-10-22 08:30:00", "99.00", "buyerB");

        MetricsPO shop1 = orderStatsService.dailyMetrics(1, LocalDate.parse("2026-10-22"));
        MetricsPO shop2 = orderStatsService.dailyMetrics(2, LocalDate.parse("2026-10-22"));

        assertEquals(1, shop1.getPayCustomers(), "店铺 1 只统计自己的买家");
        assertEquals(0, new BigDecimal("50.00").compareTo(shop1.getRefundAmount()), "店铺 1 只统计自己的退款");
        assertEquals(0, new BigDecimal("100.00").compareTo(shop1.getAvgOrderValue()), "店铺 1 只统计自己的订单");
        assertEquals(1, shop2.getPayCustomers(), "店铺 2 只统计自己的买家");
        assertEquals(0, BigDecimal.ZERO.compareTo(shop2.getRefundAmount()), "店铺 2 无自己的退款");
        assertEquals(0, new BigDecimal("99.00").compareTo(shop2.getAvgOrderValue()), "店铺 2 只统计自己的订单");
    }
}
