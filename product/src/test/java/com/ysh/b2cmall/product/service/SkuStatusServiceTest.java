package com.ysh.b2cmall.product.service;

import com.ysh.b2cmall.product.dao.mapper.SkuMapper;
import com.ysh.b2cmall.product.dao.po.SkuPO;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 上架/下架切换的 Service 层表格驱动测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-product-list.md 功能上下文 7.2.1：
 * POST /product/status 将商品设为目标状态（上架 -> 设 0；下架 -> 设 1），shopId 隔离，
 * 重复对同状态操作幂等成功。Fail 边界：只实现下架未实现上架。
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：双向切换 1->0、0->1 各断言 DB status
 * 2. 类型与格式：status 非 0/1、skuId 缺失 -> 拒绝且 DB 零改动
 * 3. 重复创建：重复设同状态幂等成功
 * 4. 非法输入：他店/不存在 skuId -> 「不存在」异常且零改动
 */
class SkuStatusServiceTest {

    @TempDir
    File tempDir;

    private SqlSession session;
    private SkuMapper skuMapper;
    private SkuServiceImpl skuService;

    private static final int OWN_ON_SHELF = 10;   // 本店在售
    private static final int OWN_OFF_SHELF = 11;  // 本店已下架
    private static final int OTHER_SHOP_SKU = 20; // 他店在售

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "product-status-test.db").getAbsolutePath();
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

        insertSku(dataSource, OWN_ON_SHELF, 1, 1);
        insertSku(dataSource, OWN_OFF_SHELF, 1, 0);
        insertSku(dataSource, OTHER_SHOP_SKU, 2, 1);

        session = sqlSessionFactory.openSession(true);
        skuMapper = session.getMapper(SkuMapper.class);
        skuService = new SkuServiceImpl();
        ReflectionTestUtils.setField(skuService, "skuMapper", skuMapper);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private void insertSku(UnpooledDataSource dataSource, int id, int shopId, int status) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_sku (id, shop_id, category_id, name, description, price, stock, type, status)"
                             + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setInt(1, id);
            ps.setInt(2, shopId);
            ps.setInt(3, 0);
            ps.setString(4, "商品" + id);
            ps.setString(5, "desc");
            ps.setBigDecimal(6, new java.math.BigDecimal("10.00"));
            ps.setInt(7, 10);
            ps.setInt(8, 1);
            ps.setInt(9, status);
            ps.executeUpdate();
        }
    }

    private int statusOf(int skuId) {
        return skuMapper.selectById(skuId).getStatus();
    }

    // ============ 1. 字段级校验：双向切换各断言 DB status ============

    /**
     * 列：用例名、目标 skuId、目标状态、期望 DB status
     */
    static Stream<Arguments> switchCases() {
        return Stream.of(
                arguments("上架商品执行下架（1->0）", OWN_ON_SHELF, 0, 0),
                arguments("下架商品执行上架（0->1）", OWN_OFF_SHELF, 1, 1)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("switchCases")
    void 上下架_双向切换断言DB(String caseName, Integer skuId, Integer targetStatus, int expectedDb) {
        skuService.updateStatus(1, skuId, targetStatus);

        assertEquals(expectedDb, statusOf(skuId), caseName + "：DB status 应为目标状态");
        assertEquals(1, statusOf(OTHER_SHOP_SKU), caseName + "：他店商品必须零改动");
    }

    // ============ 3. 重复创建：重复设同状态幂等成功 ============

    @Test
    void 重复下架_幂等成功() {
        skuService.updateStatus(1, OWN_ON_SHELF, 0);
        skuService.updateStatus(1, OWN_ON_SHELF, 0);

        assertEquals(0, statusOf(OWN_ON_SHELF), "重复下架后 status 应为 0");
    }

    // ============ 2. 类型与格式：非法参数拒绝且 DB 零改动 ============

    /**
     * 列：用例名、skuId、目标状态、期望原因关键词
     */
    static Stream<Arguments> illegalCases() {
        return Stream.of(
                arguments("状态取值非法", OWN_ON_SHELF, 2, "状态"),
                arguments("状态缺失", OWN_ON_SHELF, null, "状态"),
                arguments("商品ID缺失", null, 0, "商品")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("illegalCases")
    void 非法参数_抛中文异常且零改动(String caseName, Integer skuId, Integer targetStatus, String reasonFragment) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> skuService.updateStatus(1, skuId, targetStatus), caseName + "：必须拒绝");
        assertTrue(ex.getMessage().contains(reasonFragment),
                caseName + "：消息应含「" + reasonFragment + "」，实际：" + ex.getMessage());
        assertEquals(1, statusOf(OWN_ON_SHELF), caseName + "：DB 必须零改动");
    }

    // ============ 4. 非法输入：他店/不存在 skuId -> 「不存在」且零改动 ============

    @Test
    void 他店skuId_抛不存在且零改动() {
        SkuPO before = skuMapper.selectById(OTHER_SHOP_SKU);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> skuService.updateStatus(1, OTHER_SHOP_SKU, 0), "他店 skuId 必须按不存在处理");
        assertTrue(ex.getMessage().contains("不存在"), "消息应含「不存在」以映射 404，实际：" + ex.getMessage());
        assertEquals(before, skuMapper.selectById(OTHER_SHOP_SKU), "他店商品必须零改动");
    }

    @Test
    void 不存在skuId_抛不存在() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> skuService.updateStatus(1, 9999, 0), "不存在 skuId 必须抛「不存在」");
        assertTrue(ex.getMessage().contains("不存在"), "消息应含「不存在」以映射 404");
    }
}
