package com.ysh.order.service;

import com.ysh.order.dao.OrderMapper;
import com.ysh.order.dao.RefundMapper;
import com.ysh.order.dao.po.BuyerOrderQueryPO;
import com.ysh.order.service.impl.OrderServiceImpl;
import com.ysh.order.web.vo.BuyerOrderCardVO;
import com.ysh.order.web.vo.BuyerOrderPageVO;
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
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 买家端订单列表 + 状态计数的 Service 层真实 SQLite 测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-order-list.md 功能上下文 10.2（Service 层 - 卡片 VO 组装 + 状态计数归一化）：
 * - 10.2.1 卡片 VO：orderNo、shopName、createTime、status（原始枚举+文案）、
 *   payType（仅待发货 PAID 附带）、items[{skuImage、skuName、quantity、unitPrice}]、
 *   totalPrice、skuId（立即购买跳转）
 *   （Fail 边界：缺 skuId → 立即购买无法跳转；缺 shopName → 卡片头部空白）
 * - 10.2.2 买家身份强制：username 取自登录上下文，参数 buyerUsername 忽略
 *   （Service 层校验：filter.buyerUsername 为空时返回空而非查全表——由 DAO 兜底，本层透传）
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：卡片字段齐全（orderNo/shopName/status/文案/items/totalPrice/skuId）
 * 2. 类型与格式：待发货卡片含 payType、其他状态不含；items 为数组
 * 3. 重复创建：同一筛选重复查询结果一致（幂等）
 * 4. 非法输入：状态计数固定五 key 且无单状态为 0；已删/他人订单不出现
 *
 * 测试库为 @TempDir 下的临时 SQLite 库；陷阱数据：买家 B 订单 + 买家 A 已删订单。
 */
class BuyerOrderServiceTest {

    private static final String BUYER_A = "buyer_a";
    private static final String BUYER_B = "buyer_b";

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private OrderMapper orderMapper;
    private RefundMapper refundMapper;
    private OrderServiceImpl orderService;
    private int orderNoSeq = 0;

    private int aPaid;
    private int aShipped;
    private int aCompleted;
    private int aPending;
    private int aFailed;
    private int aGroupPending;
    private int aDeleted;
    private int bPaid;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "buyer-order-service.db").getAbsolutePath();
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
        }

        session = sqlSessionFactory.openSession(true);
        orderMapper = session.getMapper(OrderMapper.class);
        refundMapper = session.getMapper(RefundMapper.class);

        orderService = new OrderServiceImpl();
        ReflectionTestUtils.setField(orderService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(orderService, "refundMapper", refundMapper);

        aPaid = insertOrder(BUYER_A, "PAID", "2026-10-22 10:00:00", "WECHAT", 0);
        aShipped = insertOrder(BUYER_A, "SHIPPED", "2026-10-23 10:00:00", "WECHAT", 0);
        aCompleted = insertOrder(BUYER_A, "COMPLETED", "2026-10-24 10:00:00", "ALIPAY", 0);
        aPending = insertOrder(BUYER_A, "PENDING", "2026-10-25 10:00:00", null, 0);
        aFailed = insertOrder(BUYER_A, "FAILED", "2026-10-26 10:00:00", null, 0);
        aGroupPending = insertOrder(BUYER_A, "GROUP_PENDING", "2026-10-27 10:00:00", null, 0);
        aDeleted = insertOrder(BUYER_A, "PAID", "2026-10-28 10:00:00", "WECHAT", 1);
        bPaid = insertOrder(BUYER_B, "PAID", "2026-10-22 10:00:00", "WECHAT", 0);
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
                    + " status, pay_type, buyer_username, buyer_deleted, receiver_name, receiver_phone,"
                    + " receiver_address, shop_name, sku_image, unit_price, create_time)"
                    + " VALUES ('" + orderNo + "', 1, 100, '测试商品', 2, 39.80, "
                    + "'" + status + "', " + (payType == null ? "NULL" : "'" + payType + "'") + ", "
                    + "'" + buyerUsername + "', " + buyerDeleted + ", "
                    + "'张三', '13800001111', '上海市浦东新区测试路 1 号', '测试店铺', "
                    + "'https://img.example.com/a.png', 19.90, '" + createTime + "')";
            st.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
            try (ResultSet keys = st.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private BuyerOrderQueryPO filterA() {
        BuyerOrderQueryPO filter = new BuyerOrderQueryPO();
        filter.setBuyerUsername(BUYER_A);
        return filter;
    }

    // ============ 1. 字段级校验：卡片字段齐全 ============

    @Test
    void 卡片字段齐全() {
        BuyerOrderQueryPO filter = filterA();
        filter.setStatus("PAID");

        BuyerOrderPageVO result = orderService.pageBuyerOrders(filter);

        assertEquals(1, result.getTotal(), "total 应为命中总数 1（已删 PAID 不计入）");
        assertEquals(1, result.getRows().size(), "应返回 1 张卡片");
        BuyerOrderCardVO card = result.getRows().get(0);
        assertEquals(aPaid, card.getOrderId(), "orderId 正确");
        assertNotNull(card.getOrderNo(), "orderNo 非空");
        assertEquals("测试店铺", card.getShopName(), "shopName（店铺名称快照）正确");
        assertNotNull(card.getCreateTime(), "createTime 非空");
        assertEquals("PAID", card.getStatus(), "status 原始枚举值正确");
        assertEquals("待发货", card.getStatusText(), "statusText 中文文案正确");
        assertNotNull(card.getItems(), "items 非空");
        assertEquals(1, card.getItems().size(), "items 为数组且当前恒 1 条");
        assertEquals("测试商品", card.getItems().get(0).getSkuName(), "items.skuName 正确");
        assertEquals("https://img.example.com/a.png", card.getItems().get(0).getSkuImage(), "items.skuImage 正确");
        assertEquals(0, new BigDecimal("19.90").compareTo(card.getItems().get(0).getUnitPrice()), "items.unitPrice 正确");
        assertEquals(2, card.getItems().get(0).getQuantity(), "items.quantity 正确");
        assertEquals(0, new BigDecimal("39.80").compareTo(card.getTotalPrice()), "totalPrice 正确");
        assertEquals(100, card.getSkuId(), "skuId（立即购买跳转）正确");
    }

    // ============ 2. 类型与格式：待发货附带 payType、其他状态不含 ============

    static Stream<Arguments> statusTextCases() {
        return Stream.of(
                arguments("PENDING", "待付款"),
                arguments("PAID", "待发货"),
                arguments("SHIPPED", "待收货"),
                arguments("COMPLETED", "已完成"),
                arguments("FAILED", "交易失败"),
                arguments("GROUP_PENDING", "待成团")
        );
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("statusTextCases")
    void 状态文案映射正确(String status, String expectedText) {
        BuyerOrderQueryPO filter = filterA();
        filter.setStatus(status);

        BuyerOrderPageVO result = orderService.pageBuyerOrders(filter);

        assertEquals(1, result.getRows().size(), status + " 应命中 1 张卡片");
        assertEquals(expectedText, result.getRows().get(0).getStatusText(), status + " 文案错误");
    }

    @Test
    void 待发货卡片附带payType_其他状态不含() {
        BuyerOrderQueryPO paidFilter = filterA();
        paidFilter.setStatus("PAID");
        BuyerOrderCardVO paidCard = orderService.pageBuyerOrders(paidFilter).getRows().get(0);
        assertEquals("WECHAT", paidCard.getPayType(), "待发货卡片应附带 payType");

        BuyerOrderQueryPO shippedFilter = filterA();
        shippedFilter.setStatus("SHIPPED");
        BuyerOrderCardVO shippedCard = orderService.pageBuyerOrders(shippedFilter).getRows().get(0);
        assertEquals(null, shippedCard.getPayType(), "非待发货卡片不应附带 payType");
    }

    // ============ 3. 重复创建：重复查询一致 ============

    @Test
    void 同一筛选重复查询_结果一致() {
        BuyerOrderQueryPO filter = filterA();

        List<Integer> first = orderIdsOf(orderService.pageBuyerOrders(filter));
        List<Integer> second = orderIdsOf(orderService.pageBuyerOrders(filter));

        assertEquals(first, second, "同一筛选重复查询结果应一致");
    }

    private List<Integer> orderIdsOf(BuyerOrderPageVO result) {
        List<Integer> ids = new ArrayList<>();
        for (BuyerOrderCardVO card : result.getRows()) {
            ids.add(card.getOrderId());
        }
        return ids;
    }

    // ============ 4. 非法输入：计数固定五 key、已删/他人不出现 ============

    @Test
    void 状态计数_固定五key且无单状态为0() {
        BuyerStatusCountsVO counts = orderService.buyerStatusCounts(BUYER_A);

        assertEquals(6, counts.getAll(), "全部 = 未删除订单总数 6");
        assertEquals(1, counts.getPending(), "待付款 1");
        assertEquals(1, counts.getPaid(), "待发货 1（已删 PAID 不计入）");
        assertEquals(1, counts.getShipped(), "待收货 1");
        assertEquals(1, counts.getCompleted(), "已完成 1");
    }

    @Test
    void 状态计数_无单买家全零() {
        BuyerStatusCountsVO counts = orderService.buyerStatusCounts("nobody");

        assertEquals(0, counts.getAll(), "无单买家 all=0");
        assertEquals(0, counts.getPending(), "无单买家 pending=0");
        assertEquals(0, counts.getPaid(), "无单买家 paid=0");
        assertEquals(0, counts.getShipped(), "无单买家 shipped=0");
        assertEquals(0, counts.getCompleted(), "无单买家 completed=0");
    }

    @Test
    void 已删与待成团订单_计入全部但不计入五状态() {
        BuyerStatusCountsVO counts = orderService.buyerStatusCounts(BUYER_A);

        // 全部含 FAILED/GROUP_PENDING（6 单：5 常规 + FAILED + GROUP_PENDING，已删不计）
        assertEquals(6, counts.getAll(), "全部应含 FAILED/GROUP_PENDING，已删不计入");
        // 五个固定 key 中不含 FAILED/GROUP_PENDING，故 pending+paid+shipped+completed = 4
        assertEquals(4,
                counts.getPending() + counts.getPaid() + counts.getShipped() + counts.getCompleted(),
                "五个固定 key 之和应 = 4（不含 FAILED/GROUP_PENDING）");
    }

    @Test
    void 他人买家订单_不出现() {
        BuyerOrderQueryPO filter = filterA();

        List<Integer> ids = orderIdsOf(orderService.pageBuyerOrders(filter));

        assertTrue(!ids.contains(bPaid), "买家 A 不得看到买家 B 订单");
    }
}
