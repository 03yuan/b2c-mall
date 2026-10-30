package com.ysh.order.dao;

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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 收藏/取消收藏的 DAO 真库测试（TDD 红阶段）。
 * 对应 .tdd/task/mall-order-manage.md 功能上下文 6.1（DAO 层 - 收藏/取消收藏 SQL）：
 * - 6.1.1 收藏 UPDATE tb_order SET buyer_fav=1, update_time=now WHERE id=? AND buyer_username=? AND buyer_deleted=0
 *   （affect 0 行 = 无权/不存在/已删）
 *   （Fail 边界：漏 buyer_username -> 用户可收藏任意订单；漏 buyer_deleted -> 已删订单被复活操作）
 * - 6.1.2 取消收藏同构 SQL 置 0，幂等（重复取消返回成功、零影响亦可）
 *   （Fail 边界：取消接口与收藏接口共享 SQL 却 copy-paste 错列 -> fav 恒 1）
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：本人订单收藏 -> 1 行且 buyer_fav=1
 * 2. 类型与格式：取消收藏置 0；收藏后再取消 fav=0
 * 3. 重复创建：重复取消幂等（已 0 再取消 0 行）
 * 4. 非法输入：他人/不存在/已删订单收藏或取消 -> 0 行零改动
 */
class BuyerFavMapperTest {

    private static final String BUYER_A = "buyer_a";
    private static final String BUYER_B = "buyer_b";

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private OrderMapper orderMapper;
    private int orderNoSeq = 0;

    private int aPending;     // 买家 A 未删未收藏
    private int aDeleted;     // 买家 A 已删
    private int bPending;     // 买家 B 未删未收藏

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "buyer-fav.db").getAbsolutePath();
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

        aPending = insertOrder(BUYER_A, 0);
        aDeleted = insertOrder(BUYER_A, 1);
        bPending = insertOrder(BUYER_B, 0);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private int insertOrder(String buyerUsername, int buyerDeleted) throws Exception {
        orderNoSeq++;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_order (order_no, shop_id, sku_id, sku_name, quantity, total_price,"
                             + " status, receiver_name, receiver_phone, receiver_address,"
                             + " buyer_username, buyer_deleted)"
                             + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "NO-" + orderNoSeq);
            ps.setInt(2, 1);
            ps.setInt(3, 100);
            ps.setString(4, "测试商品");
            ps.setInt(5, 2);
            ps.setBigDecimal(6, new java.math.BigDecimal("39.80"));
            ps.setString(7, "PENDING");
            ps.setString(8, "张三");
            ps.setString(9, "13800001111");
            ps.setString(10, "上海市浦东新区测试路 1 号");
            ps.setString(11, buyerUsername);
            ps.setInt(12, buyerDeleted);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private int favOf(int id) {
        return orderMapper.selectById(id).getBuyerFav();
    }

    // ============ 1. 字段级校验：本人订单收藏 -> 1 行且 fav=1 ============

    @Test
    void 收藏本人订单_影响一行且fav为1() {
        int affected = orderMapper.favByBuyer(aPending, BUYER_A);

        assertEquals(1, affected, "收藏本人未删订单应影响 1 行");
        assertEquals(1, favOf(aPending), "收藏后 buyer_fav 应为 1");
    }

    // ============ 2. 类型与格式：取消置 0 / 收藏后取消 fav=0 ============

    @Test
    void 取消收藏本人订单_影响一行且fav为0() {
        orderMapper.favByBuyer(aPending, BUYER_A);   // 先收藏
        int affected = orderMapper.unfavByBuyer(aPending, BUYER_A);

        assertEquals(1, affected, "取消收藏应影响 1 行");
        assertEquals(0, favOf(aPending), "取消收藏后 buyer_fav 应为 0");
    }

    @Test
    void 对未收藏订单取消_幂等成功fav仍0() {
        int affected = orderMapper.unfavByBuyer(aPending, BUYER_A);

        // 幂等：未收藏订单取消，fav 保持 0（受影响行数可为 1 或 0，但 fav 必须仍为 0）
        assertEquals(0, favOf(aPending), "未收藏订单取消后 buyer_fav 仍应为 0");
    }

    // ============ 3. 重复创建：重复取消幂等 ============

    @Test
    void 重复取消_状态幂等fav仍0() {
        orderMapper.favByBuyer(aPending, BUYER_A);
        int first = orderMapper.unfavByBuyer(aPending, BUYER_A);
        int second = orderMapper.unfavByBuyer(aPending, BUYER_A);

        assertEquals(1, first, "首次取消应影响 1 行");
        // 取消 SQL 同构于收藏（不加 AND buyer_fav=? 前置条件），重复取消把 0 再写一次，
        // 任务文档 6.1.2 契约是「重复取消返回成功、零影响亦可」——即状态幂等（fav 仍 0），而非行数幂等
        assertEquals(0, favOf(aPending), "重复取消后 buyer_fav 仍为 0（状态幂等）");
    }

    // ============ 4. 非法输入：他人/不存在/已删订单 -> 0 行零改动 ============

    static Stream<Arguments> crossIdentityFavCases() {
        return Stream.of(
                arguments("收藏他人订单零改动", "OTHER", 0),
                arguments("收藏不存在订单零改动", "NOT_EXISTS", 0),
                arguments("收藏已删订单零改动", "DELETED", 0)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("crossIdentityFavCases")
    void 收藏_跨身份零改动(String caseName, String target, int expectedAffected) {
        int orderId = switch (target) {
            case "OTHER" -> bPending;
            case "NOT_EXISTS" -> 99999;
            default -> aDeleted;
        };

        int affected = orderMapper.favByBuyer(orderId, BUYER_A);

        assertEquals(expectedAffected, affected, caseName + "：受影响行数不正确");
    }

    @Test
    void 收藏他人订单_目标fav原样() {
        orderMapper.favByBuyer(bPending, BUYER_A);

        assertEquals(0, favOf(bPending), "他人订单 buyer_fav 不得被改动");
    }

    @Test
    void 取消他人订单_目标fav原样() {
        orderMapper.unfavByBuyer(bPending, BUYER_A);

        assertEquals(0, favOf(bPending), "他人订单取消收藏 buyer_fav 不得被改动");
    }

    @Test
    void 收藏已删订单_保持已删且fav不变() {
        orderMapper.favByBuyer(aDeleted, BUYER_A);

        assertEquals(0, favOf(aDeleted), "已删订单 buyer_fav 不得被改动");
        assertEquals(1, orderMapper.selectById(aDeleted).getBuyerDeleted(), "已删订单保持 buyer_deleted=1");
    }
}
