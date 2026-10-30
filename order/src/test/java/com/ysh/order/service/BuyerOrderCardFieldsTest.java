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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 买家卡片 VO 组装 shopPhone / buyerFav 两字段的 Service 层真库测试（TDD 红阶段）。
 * 对应 .tdd/task/mall-order-manage.md 功能上下文 4.2（Service/Controller 层 - 卡片 VO 组装）：
 * - 4.2.2 卡片新增 shopPhone（电话图标数据源）：读 tb_order.shop_phone 快照；null 时输出空串而非 null
 *   （Fail 边界：null 直出 -> 前端 undefined 判断漂移）
 * - 4.2.3 卡片新增 buyerFav（收藏标记）：读 tb_order.buyer_fav，恒输出 0/1
 *   （Fail 边界：字段缺失 -> 收藏按钮态无法刷新）
 * - 4.2.4 编号查询契约：orderNo 空 -> 全部；未命中 -> list 空数组（非 null）+ total=0
 *
 * 4.2.1 卡片基础字段（orderNo/shopName/status/statusText/payType/items/totalPrice/skuId）
 * 已由 BuyerOrderServiceTest 覆盖，本测试只聚焦「新增 shopPhone/buyerFav 两字段 + 编号查询契约」。
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：shopPhone 有值 -> 原样透出；buyerFav=1 -> 输出 1
 * 2. 类型与格式：shopPhone null -> 空串；buyerFav=0 -> 输出 0
 * 3. 重复创建：重复查询两字段值一致
 * 4. 非法输入：orderNo 空 = 全部；假编号 -> 空数组 + total=0
 */
class BuyerOrderCardFieldsTest {

    private static final String BUYER_A = "buyer_a";

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private OrderMapper orderMapper;
    private OrderServiceImpl orderService;

    private int favWithPhone;      // buyer_fav=1、shop_phone 有值
    private int noFavNoPhone;      // buyer_fav=0、shop_phone null

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "buyer-card-fields.db").getAbsolutePath();
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

        favWithPhone = insertOrder("NO-FAV-PHONE", 1, "13800000001");
        noFavNoPhone = insertOrder("NO-NONE", 0, null);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private int insertOrder(String orderNo, int buyerFav, String shopPhone) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                             + " status, pay_type, receiver_name, receiver_phone, receiver_address,"
                             + " buyer_username, buyer_deleted, buyer_fav, shop_phone, shop_name, sku_image, unit_price)"
                             + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, orderNo);
            ps.setInt(2, 1);
            ps.setInt(3, 100);
            ps.setString(4, "测试商品");
            ps.setInt(5, 2);
            ps.setBigDecimal(6, new java.math.BigDecimal("39.80"));
            ps.setString(7, "PAID");
            ps.setString(8, "WECHAT");
            ps.setString(9, "张三");
            ps.setString(10, "13800001111");
            ps.setString(11, "上海市浦东新区测试路 1 号");
            ps.setString(12, BUYER_A);
            ps.setInt(13, 0);
            ps.setInt(14, buyerFav);
            ps.setString(15, shopPhone);
            ps.setString(16, "测试店铺");
            ps.setString(17, "https://img.example.com/a.png");
            ps.setBigDecimal(18, new java.math.BigDecimal("19.90"));
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

    // ============ 1. 字段级校验：shopPhone 有值原样透出 / buyerFav=1 输出 1 ============

    @Test
    void 卡片_shopPhone有值原样透出() {
        BuyerOrderPageVO result = orderService.pageBuyerOrders(filterA());

        BuyerOrderCardVO card = findCard(result.getRows(), favWithPhone);
        assertNotNull(card, "应含收藏+有电话的卡片");
        assertEquals("13800000001", card.getShopPhone(), "shopPhone 应原样透出快照值");
    }

    @Test
    void 卡片_buyerFav为1输出1() {
        BuyerOrderPageVO result = orderService.pageBuyerOrders(filterA());

        BuyerOrderCardVO card = findCard(result.getRows(), favWithPhone);
        assertNotNull(card, "应含已收藏卡片");
        assertEquals(1, card.getBuyerFav(), "已收藏 buyerFav 应输出 1");
    }

    // ============ 2. 类型与格式：shopPhone null -> 空串 / buyerFav=0 输出 0 ============

    @Test
    void 卡片_shopPhone为null输出空串而非null() {
        BuyerOrderPageVO result = orderService.pageBuyerOrders(filterA());

        BuyerOrderCardVO card = findCard(result.getRows(), noFavNoPhone);
        assertNotNull(card, "应含无电话卡片");
        assertEquals("", card.getShopPhone(), "shopPhone 为 null 时应输出空串而非 null");
    }

    @Test
    void 卡片_buyerFav为0输出0() {
        BuyerOrderPageVO result = orderService.pageBuyerOrders(filterA());

        BuyerOrderCardVO card = findCard(result.getRows(), noFavNoPhone);
        assertNotNull(card, "应含未收藏卡片");
        assertEquals(0, card.getBuyerFav(), "未收藏 buyerFav 应输出 0 而非 null");
    }

    // ============ 3. 重复创建：重复查询两字段值一致 ============

    @Test
    void 重复查询_两字段值一致() {
        BuyerOrderCardVO first = findCard(orderService.pageBuyerOrders(filterA()).getRows(), favWithPhone);
        BuyerOrderCardVO second = findCard(orderService.pageBuyerOrders(filterA()).getRows(), favWithPhone);

        assertEquals(first.getShopPhone(), second.getShopPhone(), "重复查询 shopPhone 应一致");
        assertEquals(first.getBuyerFav(), second.getBuyerFav(), "重复查询 buyerFav 应一致");
    }

    // ============ 4. 非法输入：orderNo 空 = 全部 / 假编号 -> 空数组 + total=0 ============

    @Test
    void orderNo为空_返回全部() {
        BuyerOrderQueryPO filter = filterA();  // orderNo 为 null

        BuyerOrderPageVO result = orderService.pageBuyerOrders(filter);

        assertEquals(2, result.getTotal(), "orderNo 为空应返回全部 2 条");
        assertEquals(2, result.getRows().size(), "orderNo 为空应返回全部卡片");
    }

    @Test
    void 假编号_返回空数组而非null() {
        BuyerOrderQueryPO filter = filterA();
        filter.setOrderNo("NO-NOT-EXIST");

        BuyerOrderPageVO result = orderService.pageBuyerOrders(filter);

        assertEquals(0, result.getTotal(), "假编号 total 应为 0");
        assertNotNull(result.getRows(), "假编号 rows 应为空数组而非 null");
        assertTrue(result.getRows().isEmpty(), "假编号 rows 应为空数组");
    }

    static Stream<Arguments> buyerFavCases() {
        return Stream.of(
                arguments("已收藏输出1", 1, 1),
                arguments("未收藏输出0", 0, 0)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("buyerFavCases")
    void buyerFav_恒输出0或1(String caseName, int writtenFav, int expectedFav) throws Exception {
        int id = insertOrder("NO-FAV-" + writtenFav, writtenFav, null);

        BuyerOrderCardVO card = findCard(orderService.pageBuyerOrders(filterA()).getRows(), id);

        assertNotNull(card, caseName + "：应读到该卡片");
        assertEquals(expectedFav, card.getBuyerFav(), caseName + "：buyerFav 输出不正确");
    }
}
