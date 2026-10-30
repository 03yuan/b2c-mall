package com.ysh.order.service;

import com.ysh.order.dao.OrderMapper;
import com.ysh.order.dao.po.BuyerOrderQueryPO;
import com.ysh.order.service.impl.OrderServiceImpl;
import com.ysh.order.web.vo.BuyerOrderCardVO;
import com.ysh.order.web.vo.BuyerOrderPageVO;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 买家「立即购买（复购）」数据契约测试（TDD）。
 * 对应 .tdd/task/mall-order-manage.md 功能上下文 12.1（数据契约层 - 卡片 VO 含 skuId）：
 * - 12.1.1 立即购买 = 前端拿卡片 skuId 跳转商品下单页重新下单，后端无新接口；
 *   契约保障点是卡片 VO 恒含 skuId（与库 sku_id 一致）。
 *   Fail 边界：skuId 漏组装 -> 复购按钮死链。
 * - 12.1.2 下单页为商品侧既有 mall 功能，无后端红测试（责任边界见跳转链路注释）。
 *
 * 现状：buildBuyerCard 已 setSkuId(order.getSkuId())，selectBuyerPage 已读 o.sku_id，
 * 因此本测试为【已实现·回归】契约补强，预期直接绿。
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：卡片 skuId 非空且与库 sku_id 一致
 * 2. 类型与格式：不同 sku_id 的订单各自透出正确 skuId（防止串单）
 * 3. 重复创建：重复查询 skuId 一致（幂等）
 * 4. 非法输入：他人订单 skuId 不可见（隔离回归，见 4.1.1）
 */
class BuyerRebuyContractTest {

    private static final String BUYER_A = "buyer_a";
    private static final String BUYER_B = "buyer_b";

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private OrderMapper orderMapper;
    private OrderServiceImpl orderService;

    private int orderA;   // 买家 A，sku_id=100
    private int orderB;   // 买家 B，sku_id=999（陷阱：不应出现在 A 的列表）

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "buyer-rebuy.db").getAbsolutePath();
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

        orderA = insertOrder(BUYER_A, "NO-A", 100);
        orderB = insertOrder(BUYER_B, "NO-B", 999);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private int insertOrder(String buyerUsername, String orderNo, int skuId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                             + " status, pay_type, receiver_name, receiver_phone, receiver_address,"
                             + " buyer_username, buyer_deleted)"
                             + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, orderNo);
            ps.setInt(2, 1);
            ps.setInt(3, skuId);
            ps.setString(4, "商品-" + skuId);
            ps.setInt(5, 1);
            ps.setBigDecimal(6, new java.math.BigDecimal("99.00"));
            ps.setString(7, "PAID");
            ps.setString(8, "WECHAT");
            ps.setString(9, "张三");
            ps.setString(10, "13800001111");
            ps.setString(11, "上海市测试路 1 号");
            ps.setString(12, buyerUsername);
            ps.setInt(13, 0);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
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

    private BuyerOrderCardVO findCard(List<BuyerOrderCardVO> cards, int orderId) {
        for (BuyerOrderCardVO card : cards) {
            if (card.getOrderId() == orderId) {
                return card;
            }
        }
        return null;
    }

    // ============ 1. 字段级校验：skuId 非空且与库 sku_id 一致 ============

    @Test
    void 卡片_skuId非空且与库一致() {
        BuyerOrderPageVO result = orderService.pageBuyerOrders(filterA());

        BuyerOrderCardVO card = findCard(result.getRows(), orderA);
        assertNotNull(card, "应读到买家 A 的卡片");
        assertNotNull(card.getSkuId(), "skuId 不应为 null（立即购买跳转依赖）");
        assertEquals(100, card.getSkuId(), "skuId 应与库 sku_id 一致");
    }

    // ============ 2. 类型与格式：不同 sku_id 各自透出正确值（防串单） ============

    static Stream<Arguments> skuIdCases() {
        return Stream.of(
                arguments("skuId100", 100),
                arguments("skuId2026", 2026),
                arguments("skuId7", 7)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("skuIdCases")
    void 卡片_不同skuId各自透出正确值(String caseName, int skuId) throws Exception {
        int id = insertOrder(BUYER_A, "NO-SKU-" + skuId, skuId);

        BuyerOrderCardVO card = findCard(orderService.pageBuyerOrders(filterA()).getRows(), id);

        assertNotNull(card, caseName + "：应读到该卡片");
        assertEquals(skuId, card.getSkuId(), caseName + "：skuId 透出错误");
    }

    // ============ 3. 重复创建：重复查询 skuId 一致（幂等） ============

    @Test
    void 重复查询_skuId一致() {
        BuyerOrderCardVO first = findCard(orderService.pageBuyerOrders(filterA()).getRows(), orderA);
        BuyerOrderCardVO second = findCard(orderService.pageBuyerOrders(filterA()).getRows(), orderA);

        assertNotNull(first, "首次查询应读到卡片");
        assertEquals(first.getSkuId(), second.getSkuId(), "重复查询 skuId 应一致");
    }

    // ============ 4. 非法输入：他人订单 skuId 不可见（隔离回归） ============

    @Test
    void 他人订单_不可见() {
        BuyerOrderPageVO result = orderService.pageBuyerOrders(filterA());

        assertNotNull(findCard(result.getRows(), orderA), "买家 A 应看到自己的订单");
        assertEquals(null, findCard(result.getRows(), orderB), "买家 A 不应看到买家 B 的订单（隔离）");
    }
}
