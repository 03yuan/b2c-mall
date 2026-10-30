package com.ysh.b2cmall.product.mq;

import com.ysh.b2cmall.product.dao.mapper.SkuMapper;
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
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 真实销量回写消费者的表格驱动测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-product-list.md 功能上下文 8.1：
 * - 8.1.1 消费 PAY_SUCCESS 事件按 skuId 累加 sales（累加非覆盖，Fail 边界：写成覆盖 -> 累计销量丢失）
 * - 8.1.2 无效/未知事件忽略并 warn，不抛异常（Fail 边界：抛异常 -> MQ 重试风暴）
 * - 8.1.3 同一 orderId 重复投递只累加一次（Fail 边界：至少一次语义下重复累加 -> 销量虚高）
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：quantity 原样累加到 sales
 * 2. 类型与格式：tag 必须精确为 PAY_SUCCESS
 * 3. 重复创建：同一 orderId 幂等
 * 4. 非法输入：畸形 JSON/缺字段/未知 tag/未知 skuId 一律忽略不抛
 */
class SalesEventConsumerTest {

    @TempDir
    File tempDir;

    private SqlSession session;
    private SkuMapper skuMapper;
    private SalesEventConsumer consumer;
    private UnpooledDataSource dataSource;

    private static final int SKU_ID = 10;
    private static final int INITIAL_SALES = 5;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "product-sales-test.db").getAbsolutePath();
        dataSource = new UnpooledDataSource("org.sqlite.JDBC", jdbcUrl, null, null);

        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        try (InputStream in = Resources.getResourceAsStream("mapper/SkuMapper.xml")) {
            new XMLMapperBuilder(in, configuration, "mapper/SkuMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);

        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            // 最小表：本测试只关心 sales 累加与幂等去重
            st.executeUpdate("CREATE TABLE tb_sku (id INTEGER PRIMARY KEY, shop_id INTEGER, sales INTEGER)");
            st.executeUpdate("CREATE TABLE tb_processed_event (order_id INTEGER PRIMARY KEY)");
            st.executeUpdate("INSERT INTO tb_sku (id, shop_id, sales) VALUES (" + SKU_ID + ", 1, " + INITIAL_SALES + ")");
        }

        session = sqlSessionFactory.openSession(true);
        skuMapper = session.getMapper(SkuMapper.class);
        consumer = new SalesEventConsumer(skuMapper);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private int readSales() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT sales FROM tb_sku WHERE id = ?")) {
            ps.setInt(1, SKU_ID);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private String paySuccessBody(long orderId, int quantity) {
        return "{\"shopId\":1,\"orderId\":" + orderId + ",\"skuId\":" + SKU_ID
                + ",\"quantity\":" + quantity + ",\"timestamp\":1730000000000}";
    }

    // ============ 1. 字段级校验：quantity 原样累加（累加非覆盖） ============

    @ParameterizedTest(name = "quantity={0} -> sales=" + INITIAL_SALES + "+{0}")
    @ValueSource(ints = {1, 3, 10})
    void 支付成功事件_销量累加非覆盖(int quantity) throws Exception {
        consumer.onMessage("PAY_SUCCESS", paySuccessBody(1001L, quantity));

        assertEquals(INITIAL_SALES + quantity, readSales(),
                "sales 必须累加（" + INITIAL_SALES + "+" + quantity + "），覆盖赋值会丢累计销量");
    }

    // ============ 2. 类型与格式：不同 orderId 连续事件各自累加 ============

    @Test
    void 不同订单事件_各自累加() throws Exception {
        consumer.onMessage("PAY_SUCCESS", paySuccessBody(1001L, 3));
        consumer.onMessage("PAY_SUCCESS", paySuccessBody(1002L, 2));

        assertEquals(INITIAL_SALES + 3 + 2, readSales(), "不同 orderId 的事件应各自累加");
    }

    // ============ 3. 重复创建：同一 orderId 幂等只累加一次 ============

    @Test
    void 同一订单重复投递_只累加一次() throws Exception {
        String body = paySuccessBody(1001L, 3);

        consumer.onMessage("PAY_SUCCESS", body);
        consumer.onMessage("PAY_SUCCESS", body);

        assertEquals(INITIAL_SALES + 3, readSales(), "MQ 至少一次语义下重复投递不得重复累加");
    }

    // ============ 4. 非法输入：无效/未知事件忽略不抛，sales 不变（表格驱动） ============

    /**
     * 列：用例名、tag、body
     */
    static Stream<Arguments> invalidEvents() {
        return Stream.of(
                arguments("畸形 JSON", "PAY_SUCCESS", "{not-json"),
                arguments("缺 skuId", "PAY_SUCCESS", "{\"shopId\":1,\"orderId\":2001,\"quantity\":3}"),
                arguments("缺 quantity", "PAY_SUCCESS", "{\"shopId\":1,\"orderId\":2001,\"skuId\":10}"),
                arguments("缺 orderId（无法幂等去重）", "PAY_SUCCESS", "{\"shopId\":1,\"skuId\":10,\"quantity\":3}"),
                arguments("未知 tag（REFUND_APPLY 不回写销量）", "REFUND_APPLY",
                        "{\"shopId\":1,\"orderId\":2001,\"skuId\":10,\"quantity\":3}"),
                arguments("未知 skuId", "PAY_SUCCESS",
                        "{\"shopId\":1,\"orderId\":2001,\"skuId\":999,\"quantity\":3}")
        );
    }

    @ParameterizedTest(name = "{0} -> 忽略不抛")
    @MethodSource("invalidEvents")
    void 无效或未知事件_忽略不抛且销量不变(String caseName, String tag, String body) throws Exception {
        assertDoesNotThrow(() -> consumer.onMessage(tag, body),
                caseName + "：必须忽略且不抛异常（防 MQ 重试风暴）");
        assertEquals(INITIAL_SALES, readSales(), caseName + "：销量必须不变");
    }
}
