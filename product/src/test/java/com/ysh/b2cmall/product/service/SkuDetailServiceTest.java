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
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 单品编辑（详情 + 更新）的 Service 层表格驱动测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-product-list.md 功能上下文 7.1：
 * - 7.1.1 详情：shopId 隔离，他店/不存在 -> 404（消息含「不存在」）
 *   （Fail 边界：漏字段编辑页显示残缺）
 * - 7.1.2 更新：基础信息/价格/库存/图文/配送/排序/类型/类别/注水销量，字段校验同导入；
 *   不改 sales；他店 skuId -> 404 且零改动
 *   （Fail 边界：更新语句覆盖 shopId（商品被"搬店"））
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：更新后各字段读回一致
 * 2. 类型与格式：非法字段值（负价格/非法枚举/缺名称）-> 400 语义异常且 DB 零改动
 * 3. 重复创建：同请求重复更新幂等成功、结果一致
 * 4. 非法输入：他店/不存在 skuId -> 「不存在」异常且他店数据零改动；sales 不被覆盖
 *
 * 固定数据：本店商品 id=10（sales=100）；他店商品 id=20（shopId=2）。
 */
class SkuDetailServiceTest {

    @TempDir
    File tempDir;

    private SqlSession session;
    private SkuMapper skuMapper;
    private SkuServiceImpl skuService;

    private static final int OWN_SKU_ID = 10;
    private static final int OTHER_SHOP_SKU_ID = 20;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "product-detail-test.db").getAbsolutePath();
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
                    + "market_price DECIMAL(10,2),"
                    + "stock INTEGER DEFAULT 0,"
                    + "stock_threshold INTEGER DEFAULT 0,"
                    + "sales INTEGER DEFAULT 0,"
                    + "fake_sales INTEGER DEFAULT 0,"
                    + "store_stock INTEGER DEFAULT 0,"
                    + "type TINYINT NOT NULL,"
                    + "product_type TINYINT DEFAULT 1,"
                    + "images VARCHAR(1000),"
                    + "spec VARCHAR(500),"
                    + "delivery_methods VARCHAR(100),"
                    + "sort INTEGER DEFAULT 0,"
                    + "status TINYINT DEFAULT 0,"
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
        }

        insertSku(dataSource, OWN_SKU_ID, 1, "本店商品", 100);
        insertSku(dataSource, OTHER_SHOP_SKU_ID, 2, "他店商品", 50);

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

    private void insertSku(UnpooledDataSource dataSource, int id, int shopId, String name, int sales) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_sku (id, shop_id, category_id, name, description, price, market_price,"
                             + " stock, store_stock, sales, fake_sales, type, product_type, images, spec,"
                             + " delivery_methods, sort, status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setInt(1, id);
            ps.setInt(2, shopId);
            ps.setInt(3, 100);
            ps.setString(4, name);
            ps.setString(5, "旧描述");
            ps.setBigDecimal(6, new BigDecimal("10.00"));
            ps.setBigDecimal(7, new BigDecimal("20.00"));
            ps.setInt(8, 7);
            ps.setInt(9, 3);
            ps.setInt(10, sales);
            ps.setInt(11, 5);
            ps.setInt(12, 1);
            ps.setInt(13, 1);
            ps.setString(14, "a.jpg,b.jpg");
            ps.setString(15, "默认规格");
            ps.setString(16, "EXPRESS");
            ps.setInt(17, 1);
            ps.setInt(18, 1);
            ps.executeUpdate();
        }
    }

    private SkuPO validUpdatePo() {
        SkuPO po = new SkuPO();
        po.setId(OWN_SKU_ID);
        po.setCategoryId(101);
        po.setName("改名商品");
        po.setDescription("新描述");
        po.setPrice(new BigDecimal("9.90"));
        po.setMarketPrice(new BigDecimal("19.90"));
        po.setStock(8);
        po.setStoreStock(4);
        po.setFakeSales(66);
        po.setType(2);
        po.setProductType(2);
        po.setImages("c.jpg");
        po.setSpec("新规格");
        po.setDeliveryMethods("CITY,PICKUP");
        po.setSort(9);
        return po;
    }

    // ============ 7.1.1 详情：本店返回全部字段；他店/不存在 -> 「不存在」 ============

    @Test
    void 详情_本店商品返回编辑页全部字段() {
        SkuPO detail = skuService.getDetail(1, OWN_SKU_ID);

        assertNotNull(detail, "本店商品应可查");
        assertEquals(OWN_SKU_ID, detail.getId().intValue(), "skuId 应正确");
        assertEquals("本店商品", detail.getName(), "名称应返回");
        assertEquals("旧描述", detail.getDescription(), "描述应返回");
        assertEquals(100, detail.getSales().intValue(), "真实销量应返回");
        assertEquals(5, detail.getFakeSales().intValue(), "注水销量应返回");
        assertEquals(7, detail.getStock().intValue(), "仓库库存应返回");
        assertEquals(3, detail.getStoreStock().intValue(), "门店库存应返回");
        assertEquals("a.jpg,b.jpg", detail.getImages(), "图文应返回");
        assertEquals("默认规格", detail.getSpec(), "规格应返回");
        assertEquals("EXPRESS", detail.getDeliveryMethods(), "配送方式应返回");
        assertEquals(1, detail.getSort().intValue(), "排序应返回");
        assertEquals(1, detail.getStatus().intValue(), "状态应返回");
        assertEquals(1, detail.getProductType().intValue(), "商品类型应返回");
        assertEquals(1, detail.getType().intValue(), "商品类别应返回");
    }

    @Test
    void 详情_他店商品_抛不存在() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> skuService.getDetail(1, OTHER_SHOP_SKU_ID), "他店商品必须按不存在处理");
        assertTrue(ex.getMessage().contains("不存在"), "消息应含「不存在」以映射 404，实际：" + ex.getMessage());
    }

    @Test
    void 详情_不存在商品_抛不存在() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> skuService.getDetail(1, 9999), "不存在商品必须抛「不存在」");
        assertTrue(ex.getMessage().contains("不存在"), "消息应含「不存在」以映射 404");
    }

    // ============ 7.1.2 字段级校验：合法更新读回一致 ============

    @Test
    void 更新_合法字段读回一致() {
        skuService.updateSku(1, validUpdatePo());

        SkuPO updated = skuMapper.selectById(OWN_SKU_ID);
        assertEquals("改名商品", updated.getName(), "名称应更新");
        assertEquals("新描述", updated.getDescription(), "描述应更新");
        assertEquals(0, new BigDecimal("9.90").compareTo(updated.getPrice()), "销售价应更新");
        assertEquals(0, new BigDecimal("19.90").compareTo(updated.getMarketPrice()), "市场价应更新");
        assertEquals(8, updated.getStock().intValue(), "仓库库存应更新");
        assertEquals(4, updated.getStoreStock().intValue(), "门店库存应更新");
        assertEquals(66, updated.getFakeSales().intValue(), "注水销量应更新");
        assertEquals(2, updated.getType().intValue(), "商品类别应更新");
        assertEquals(2, updated.getProductType().intValue(), "商品类型应更新");
        assertEquals("c.jpg", updated.getImages(), "图文应更新");
        assertEquals("新规格", updated.getSpec(), "规格应更新");
        assertEquals("CITY,PICKUP", updated.getDeliveryMethods(), "配送方式应更新");
        assertEquals(9, updated.getSort().intValue(), "排序应更新");
        assertEquals(101, updated.getCategoryId().intValue(), "分类应更新");
        assertEquals(1, updated.getShopId().intValue(), "shopId 必须不被「搬店」");
    }

    // ============ 7.1.2 非法输入：sales 保护 + 他店/不存在 404 零改动 ============

    @Test
    void 更新_sales不被覆盖() {
        SkuPO po = validUpdatePo();
        po.setSales(999);

        skuService.updateSku(1, po);

        assertEquals(100, skuMapper.selectById(OWN_SKU_ID).getSales().intValue(),
                "真实销量必须保持 DB 原值 100，不被更新请求覆盖");
    }

    @Test
    void 更新_他店skuId_抛不存在且零改动() {
        SkuPO before = skuMapper.selectById(OTHER_SHOP_SKU_ID);
        SkuPO po = validUpdatePo();
        po.setId(OTHER_SHOP_SKU_ID);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> skuService.updateSku(1, po), "他店 skuId 必须按不存在处理");
        assertTrue(ex.getMessage().contains("不存在"), "消息应含「不存在」以映射 404");
        assertEquals(before, skuMapper.selectById(OTHER_SHOP_SKU_ID), "他店商品必须零改动");
    }

    @Test
    void 更新_不存在skuId_抛不存在() {
        SkuPO po = validUpdatePo();
        po.setId(9999);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> skuService.updateSku(1, po), "不存在 skuId 必须抛「不存在」");
        assertTrue(ex.getMessage().contains("不存在"), "消息应含「不存在」以映射 404");
    }

    // ============ 7.1.2 类型与格式：非法字段值 -> 异常且 DB 零改动（表格驱动） ============

    /**
     * 列：用例名、对合法更新 PO 的破坏动作、期望原因关键词
     */
    static Stream<Arguments> illegalUpdateCases() {
        return Stream.of(
                arguments("名称为空", (Consumer<SkuPO>) p -> p.setName(""), "名称"),
                arguments("销售价为负", (Consumer<SkuPO>) p -> p.setPrice(new BigDecimal("-1")), "销售价"),
                arguments("市场价为负", (Consumer<SkuPO>) p -> p.setMarketPrice(new BigDecimal("-1")), "市场价"),
                arguments("仓库库存为负", (Consumer<SkuPO>) p -> p.setStock(-1), "库存"),
                arguments("门店库存为负", (Consumer<SkuPO>) p -> p.setStoreStock(-1), "库存"),
                arguments("注水销量为负", (Consumer<SkuPO>) p -> p.setFakeSales(-1), "注水销量"),
                arguments("商品类型非法", (Consumer<SkuPO>) p -> p.setProductType(9), "商品类型"),
                arguments("商品类别非法", (Consumer<SkuPO>) p -> p.setType(5), "商品类别"),
                arguments("状态非法", (Consumer<SkuPO>) p -> p.setStatus(2), "状态"),
                arguments("配送方式非法", (Consumer<SkuPO>) p -> p.setDeliveryMethods("FOO"), "配送方式")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("illegalUpdateCases")
    void 更新_非法字段_抛中文异常且零改动(String caseName, Consumer<SkuPO> corrupt, String reasonFragment) {
        SkuPO po = validUpdatePo();
        corrupt.accept(po);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> skuService.updateSku(1, po), caseName + "：必须拒绝");
        assertTrue(ex.getMessage().contains(reasonFragment),
                caseName + "：消息应含「" + reasonFragment + "」，实际：" + ex.getMessage());
        assertEquals("本店商品", skuMapper.selectById(OWN_SKU_ID).getName(), caseName + "：DB 必须零改动");
    }

    // ============ 3. 重复创建：重复更新幂等一致 ============

    @Test
    void 重复更新_幂等且结果一致() {
        skuService.updateSku(1, validUpdatePo());
        skuService.updateSku(1, validUpdatePo());

        SkuPO updated = skuMapper.selectById(OWN_SKU_ID);
        assertEquals("改名商品", updated.getName(), "重复更新后名称应一致");
        assertEquals(100, updated.getSales().intValue(), "重复更新不得影响真实销量");
    }
}
