package com.ysh.b2cmall.product.service;

import com.ysh.b2cmall.common.response.BaseResponseVO;
import com.ysh.b2cmall.product.client.OrderQueryClient;
import com.ysh.b2cmall.product.dao.mapper.SkuMapper;
import com.ysh.b2cmall.product.service.impl.SkuServiceImpl;
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
import java.sql.Statement;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 删除商品的下游容错表格驱动测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-product-list.md 功能上下文 7.3.4：
 * order-service 不可用（Feign 调用失败）-> 删除返回 5xx，宁可拒绝删除也不放行。
 * Fail 边界：吞异常放行删除 -> 数据完整性破坏。
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：任何下游故障下商品记录必须保留（拒删不放行）
 * 2. 类型与格式：故障统一转译为 DownstreamServiceException（消息「订单服务暂不可用」-> 503）
 * 3. 重复创建：下游恢复后删除可正常成功（熔断不是永久的）
 * 4. 非法输入：下游响应契约破坏（data 为 null / 缺 count 键）视同不可用，拒删
 */
class SkuDeleteFallbackTest {

    @TempDir
    File tempDir;

    private SqlSession session;
    private SkuMapper skuMapper;
    private OrderQueryClient orderQueryClient;
    private SkuServiceImpl skuService;

    private static final int OWN_SKU_ID = 10;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "product-delete-fallback-test.db").getAbsolutePath();
        UnpooledDataSource dataSource = new UnpooledDataSource("org.sqlite.JDBC", jdbcUrl, null, null);

        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        try (InputStream in = Resources.getResourceAsStream("mapper/SkuMapper.xml")) {
            new XMLMapperBuilder(in, configuration, "mapper/SkuMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);

        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS tb_sku");
            st.executeUpdate("CREATE TABLE tb_sku ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "shop_id INTEGER NOT NULL,"
                    + "category_id INTEGER DEFAULT 0,"
                    + "name VARCHAR(100) NOT NULL,"
                    + "description TEXT,"
                    + "price DECIMAL(10,2) NOT NULL,"
                    + "stock INTEGER DEFAULT 0,"
                    + "type TINYINT NOT NULL,"
                    + "images VARCHAR(1000),"
                    + "spec VARCHAR(500),"
                    + "status TINYINT DEFAULT 0,"
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_sku (id, shop_id, category_id, name, description, price, stock, type, status)"
                             + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setInt(1, OWN_SKU_ID);
            ps.setInt(2, 1);
            ps.setInt(3, 0);
            ps.setString(4, "本店商品");
            ps.setString(5, "desc");
            ps.setBigDecimal(6, new java.math.BigDecimal("10.00"));
            ps.setInt(7, 10);
            ps.setInt(8, 1);
            ps.setInt(9, 1);
            ps.executeUpdate();
        }

        session = sqlSessionFactory.openSession(true);
        skuMapper = session.getMapper(SkuMapper.class);
        orderQueryClient = mock(OrderQueryClient.class);
        skuService = new SkuServiceImpl();
        ReflectionTestUtils.setField(skuService, "skuMapper", skuMapper);
        ReflectionTestUtils.setField(skuService, "orderQueryClient", orderQueryClient);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    // ============ 1+2+4. 下游故障 -> DownstreamServiceException 且记录保留（表格驱动） ============

    /** 列：用例名、下游故障桩类型（0=Feign 抛异常 1=data 为 null 2=缺 count 键） */
    @ParameterizedTest(name = "{0}")
    @MethodSource("failureStubCases")
    void 下游故障_拒删且记录保留(String caseName, int stubKind) {
        switch (stubKind) {
            case 0:
                when(orderQueryClient.countBySku(OWN_SKU_ID))
                        .thenThrow(new RuntimeException("connection refused"));
                break;
            case 1:
                when(orderQueryClient.countBySku(OWN_SKU_ID))
                        .thenReturn(new BaseResponseVO<>(null));
                break;
            default:
                BaseResponseVO<Map<String, Object>> broken = new BaseResponseVO<>(new java.util.HashMap<>());
                when(orderQueryClient.countBySku(OWN_SKU_ID)).thenReturn(broken);
        }

        DownstreamServiceException ex = assertThrows(DownstreamServiceException.class,
                () -> skuService.deleteSku(1, OWN_SKU_ID),
                caseName + "：必须转译为 DownstreamServiceException（-> 503），不得放行删除");
        assertTrue(ex.getMessage().contains("订单服务暂不可用"),
                caseName + "：消息应含「订单服务暂不可用」，实际：" + ex.getMessage());
        assertNotNull(skuMapper.selectById(OWN_SKU_ID), caseName + "：拒删后记录必须仍在");
    }

    static Stream<Arguments> failureStubCases() {
        return Stream.of(
                arguments("Feign 连接失败抛异常", 0),
                arguments("响应 data 为 null", 1),
                arguments("响应缺 count 键", 2)
        );
    }

    // ============ 3. 重复创建：下游恢复后删除可正常成功 ============

    @Test
    void 下游恢复后_删除正常成功() {
        when(orderQueryClient.countBySku(OWN_SKU_ID))
                .thenThrow(new RuntimeException("connection refused"));
        assertThrows(DownstreamServiceException.class,
                () -> skuService.deleteSku(1, OWN_SKU_ID), "下游故障时必须拒删");
        assertNotNull(skuMapper.selectById(OWN_SKU_ID), "故障期记录必须保留");

        BaseResponseVO<Map<String, Object>> ok = new BaseResponseVO<>(new java.util.HashMap<>());
        ok.getData().put("count", 0);
        org.mockito.Mockito.doReturn(ok).when(orderQueryClient).countBySku(OWN_SKU_ID);

        skuService.deleteSku(1, OWN_SKU_ID);
        assertTrue(skuMapper.selectById(OWN_SKU_ID) == null, "下游恢复后无订单商品应可正常删除");
    }
}
