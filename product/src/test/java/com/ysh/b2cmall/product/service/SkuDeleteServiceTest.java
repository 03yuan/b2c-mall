package com.ysh.b2cmall.product.service;

import com.ysh.b2cmall.common.response.BaseResponseVO;
import com.ysh.b2cmall.product.client.OrderQueryClient;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 删除商品的 Service 层表格驱动测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-product-list.md 功能上下文 7.3.3：
 * 订单计数 > 0 -> 400「已产生订单的商品禁止删除」且不删；计数 = 0 -> 删除记录；shopId 隔离。
 * Fail 边界：不校验直接删 -> 历史订单关联到不存在的商品。
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：计数=0 -> 记录消失；计数>0 -> 记录保留
 * 2. 类型与格式：拒删消息为「已产生订单的商品禁止删除」
 * 3. 重复创建：删除后重复删除 -> 「不存在」
 * 4. 非法输入：他店 skuId -> 「不存在」且零改动、且不发起订单计数查询（不浪费跨服务调用）
 */
class SkuDeleteServiceTest {

    @TempDir
    File tempDir;

    private SqlSession session;
    private SkuMapper skuMapper;
    private OrderQueryClient orderQueryClient;
    private SkuServiceImpl skuService;

    private static final int OWN_SKU_ID = 10;
    private static final int OTHER_SHOP_SKU_ID = 20;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "product-delete-test.db").getAbsolutePath();
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

        insertSku(dataSource, OWN_SKU_ID, 1);
        insertSku(dataSource, OTHER_SHOP_SKU_ID, 2);

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

    private void insertSku(UnpooledDataSource dataSource, int id, int shopId) throws Exception {
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
            ps.setInt(9, 1);
            ps.executeUpdate();
        }
    }

    private BaseResponseVO<Map<String, Object>> countResponse(int count) {
        Map<String, Object> data = new HashMap<>();
        data.put("count", count);
        return new BaseResponseVO<>(data);
    }

    // ============ 1+2. 计数 > 0 -> 拒删且记录保留（表格驱动） ============

    @ParameterizedTest(name = "订单计数={0} -> 拒删")
    @ValueSource(ints = {1, 2, 100})
    void 有订单_拒删且记录保留(int orderCount) {
        when(orderQueryClient.countBySku(OWN_SKU_ID)).thenReturn(countResponse(orderCount));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> skuService.deleteSku(1, OWN_SKU_ID), "已产生订单的商品必须拒删");
        assertTrue(ex.getMessage().contains("已产生订单的商品禁止删除"),
                "消息应为「已产生订单的商品禁止删除」，实际：" + ex.getMessage());
        assertNotNull(skuMapper.selectById(OWN_SKU_ID), "拒删后记录必须仍在");
    }

    // ============ 1. 计数 = 0 -> 删除 ============

    @Test
    void 无订单_删除成功记录消失() {
        when(orderQueryClient.countBySku(OWN_SKU_ID)).thenReturn(countResponse(0));

        skuService.deleteSku(1, OWN_SKU_ID);

        assertNull(skuMapper.selectById(OWN_SKU_ID), "无订单商品删除后记录应消失");
        verify(orderQueryClient).countBySku(OWN_SKU_ID);
    }

    // ============ 3. 重复创建：删除后重复删除 -> 「不存在」 ============

    @Test
    void 重复删除_第二次抛不存在() {
        when(orderQueryClient.countBySku(OWN_SKU_ID)).thenReturn(countResponse(0));

        skuService.deleteSku(1, OWN_SKU_ID);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> skuService.deleteSku(1, OWN_SKU_ID), "重复删除必须抛「不存在」");
        assertTrue(ex.getMessage().contains("不存在"), "消息应含「不存在」以映射 404");
    }

    // ============ 4. 非法输入：他店 skuId -> 「不存在」零改动且不发起订单查询 ============

    @Test
    void 他店skuId_抛不存在且零改动() {
        SkuPO before = skuMapper.selectById(OTHER_SHOP_SKU_ID);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> skuService.deleteSku(1, OTHER_SHOP_SKU_ID), "他店 skuId 必须按不存在处理");
        assertTrue(ex.getMessage().contains("不存在"), "消息应含「不存在」以映射 404");
        assertEquals(before, skuMapper.selectById(OTHER_SHOP_SKU_ID), "他店商品必须零改动");
        verify(orderQueryClient, never()).countBySku(anyInt());
    }
}
