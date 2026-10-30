package com.ysh.b2cmall.shop.mq;

import com.ysh.b2cmall.shop.dao.mapper.ShopMessageMapper;
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
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 订单事件消费落库（RocketMQ Listener + tb_shop_message）测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-dashboard.md 功能上下文 5.2：
 * - 5.2.1 消费者收到事件后落库 tb_shop_message（id、shop_id、type、content、order_id、created_at），
 *   记录字段与事件一致
 *   （Fail 边界：消费失败无重试则丢消息）
 * - 5.2.2 三种消息文本模板固定格式（防漂移，测试写死整句作锚）：
 *   「用户 xxx 已支付成功，订单号 xxx，请及时处理」
 *   「有买家发起退款，订单编号 xxx，请在 7 天内处理」
 *   「订单 xxx 用户 xxx 已经确认收货」
 *   （Fail 边界：模板拼写漂移如多空格导致客服话术不统一）
 * 载荷契约与 order-service 5.1 发布一致：shopId、orderId、buyerUsername、时间戳。
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：落库记录 shop_id/type/order_id 与事件一致，content 正确嵌入订单号与买家名
 * 2. 类型与格式：content 与模板逐字一致（整句断言）、大数值/中文名原样嵌入、created_at 非空
 * 3. 重复创建：MQ at-least-once——同一事件重复投递重复落库两条、内容一致（不丢不吞）
 * 4. 非法输入：未知 tag / 载荷缺必填字段 / 非法 JSON -> 不落库（带合法对照事件：
 *    对照落 1 条而总数仍为 1，证明非法事件未混入脏记录）
 *
 * 测试库为 @TempDir 下的临时 SQLite 库（绝对路径连接串），落库断言用 JDBC 直查表。
 */
class MessageConsumerTest {

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private MessageConsumer messageConsumer;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "shop-test.db").getAbsolutePath();
        dataSource = new UnpooledDataSource("org.sqlite.JDBC", jdbcUrl, null, null);

        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        try (InputStream is = Resources.getResourceAsStream("mapper/ShopMessageMapper.xml")) {
            new XMLMapperBuilder(is, configuration, "mapper/ShopMessageMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        session = factory.openSession(true);

        // 目标 schema（5.2.1）：tb_shop_message（id、shop_id、type、content、order_id、created_at）
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE tb_shop_message (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "shop_id INTEGER NOT NULL," +
                    "type VARCHAR(20) NOT NULL," +
                    "content VARCHAR(500) NOT NULL," +
                    "order_id INTEGER NOT NULL," +
                    "created_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
        }

        ShopMessageMapper mapper = session.getMapper(ShopMessageMapper.class);
        messageConsumer = new MessageConsumer(mapper);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (session != null) {
            session.close();
        }
    }

    /** 载荷 spec：完整 / 缺shopId / 缺orderId / 缺buyer / 非法JSON（与 5.1 发布契约对齐） */
    private String buildBody(String spec, Integer shopId, Integer orderId, String buyer) {
        switch (spec) {
            case "非法JSON":
                return "{not-json";
            case "缺shopId":
                return "{\"orderId\":" + orderId + ",\"buyerUsername\":\"" + buyer
                        + "\",\"timestamp\":1700000000000}";
            case "缺orderId":
                return "{\"shopId\":" + shopId + ",\"buyerUsername\":\"" + buyer
                        + "\",\"timestamp\":1700000000000}";
            case "缺buyer":
                return "{\"shopId\":" + shopId + ",\"orderId\":" + orderId
                        + ",\"timestamp\":1700000000000}";
            default:
                return "{\"shopId\":" + shopId + ",\"orderId\":" + orderId
                        + ",\"buyerUsername\":\"" + buyer + "\",\"timestamp\":1700000000000}";
        }
    }

    /** JDBC 直查 tb_shop_message：每行 [shop_id, type, content, order_id, created_at] */
    private List<String[]> queryMessages() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT shop_id, type, content, order_id, created_at FROM tb_shop_message ORDER BY id")) {
            List<String[]> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)});
            }
            return rows;
        }
    }

    /**
     * 列：用例名、事件 tag、载荷 spec、shopId、orderId、买家名、
     *     期望 content（"-"=期望不落库）
     */
    static Stream<Arguments> consumeCases() {
        return Stream.of(
                // ----1. 字段级校验：落库字段与事件一致，content 正确嵌入订单号与买家名----
                arguments("支付成功落库字段一致", "PAY_SUCCESS", "完整", 7, 11, "buyer-小王",
                        "用户 buyer-小王 已支付成功，订单号 11，请及时处理"),
                arguments("退款申请落库字段一致", "REFUND_APPLY", "完整", 7, 12, "buyer-小王",
                        "有买家发起退款，订单编号 12，请在 7 天内处理"),
                arguments("确认收货落库字段一致", "CONFIRM_RECEIPT", "完整", 3, 13, "buyer-李四",
                        "订单 13 用户 buyer-李四 已经确认收货"),

                // ----2. 类型与格式：content 与模板逐字一致、值原样嵌入、created_at 非空（runner 断言）----
                arguments("大数值shopId与订单号原样嵌入", "PAY_SUCCESS", "完整", 4242, 99999, "buyer-9",
                        "用户 buyer-9 已支付成功，订单号 99999，请及时处理"),
                arguments("中文买家名逐字嵌入模板", "REFUND_APPLY", "完整", 3, 5, "buyer-李四",
                        "有买家发起退款，订单编号 5，请在 7 天内处理"),
                arguments("确认收货模板逐字含空格", "CONFIRM_RECEIPT", "完整", 7, 8, "buyer-甲",
                        "订单 8 用户 buyer-甲 已经确认收货"),

                // ----3. 重复创建（MQ at-least-once：重复投递重复落库）见下方独立测试----

                // ----4. 非法输入：未知 tag / 缺必填字段 / 非法 JSON -> 不落库（对照事件证明未混入脏记录）----
                arguments("未知tag不落库", "UNKNOWN_TAG", "完整", 7, 11, "buyer-小王", "-"),
                arguments("载荷缺orderId不落库", "PAY_SUCCESS", "缺orderId", 7, 11, "buyer-小王", "-"),
                arguments("载荷缺shopId不落库", "PAY_SUCCESS", "缺shopId", 7, 11, "buyer-小王", "-"),
                arguments("载荷缺买家名不落库", "PAY_SUCCESS", "缺buyer", 7, 11, "buyer-小王", "-"),
                arguments("非法JSON不落库", "PAY_SUCCESS", "非法JSON", 7, 11, "buyer-小王", "-")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("consumeCases")
    void 消费落库_字段一致与模板逐字(String caseName, String tag, String spec,
                                  Integer shopId, Integer orderId, String buyer,
                                  String expectedContent) throws Exception {
        if ("-".equals(expectedContent)) {
            // 非法输入：先消费一条合法对照事件（应落 1 条），再消费非法事件——总数仍为 1，
            // 既保证红阶段必失败，又证明非法事件未混入脏记录（混入则总数 > 1）
            messageConsumer.onMessage("PAY_SUCCESS", buildBody("完整", 7, 99, "buyer-对照"));
            messageConsumer.onMessage(tag, buildBody(spec, shopId, orderId, buyer));

            List<String[]> rows = queryMessages();
            assertEquals(1, rows.size(), "非法事件不得落库（对照事件仍应落 1 条）");
            assertEquals("99", rows.get(0)[3], "落下的应是对照事件");
        } else {
            messageConsumer.onMessage(tag, buildBody(spec, shopId, orderId, buyer));

            List<String[]> rows = queryMessages();
            assertEquals(1, rows.size(), "事件应恰好落库 1 条记录");
            String[] row = rows.get(0);
            assertEquals(String.valueOf(shopId), row[0], "shopId 归属错误则消息无法归属店铺");
            assertEquals(tag, row[1], "type 应与事件 tag 一致");
            assertEquals(expectedContent, row[2], "content 必须与模板逐字一致（防漂移）");
            assertEquals(String.valueOf(orderId), row[3], "orderId 用于前端点击跳订单详情");
            assertNotNull(row[4], "created_at 不得为空");
        }
    }

    // ----3. 重复创建：MQ at-least-once——同一事件重复投递重复落库两条、内容一致（不丢不吞）----

    @Test
    void 重复投递同一事件_重复落库且内容一致() throws Exception {
        String body = buildBody("完整", 7, 11, "buyer-小王");

        messageConsumer.onMessage("PAY_SUCCESS", body);
        messageConsumer.onMessage("PAY_SUCCESS", body);

        List<String[]> rows = queryMessages();
        assertEquals(2, rows.size(), "MQ at-least-once：重复投递重复落库，不丢不吞");
        assertEquals(rows.get(0)[2], rows.get(1)[2], "重复消费的消息内容应完全一致");
    }
}
