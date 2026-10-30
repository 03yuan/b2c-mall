package com.ysh.b2cmall.product.service;

import com.ysh.b2cmall.product.dao.mapper.CategoryMapper;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * SkuService 批量设置的表格驱动测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-product-list.md 功能上下文 4.1（Service 层 - 批量更新，按操作类型分发）：
 * - 4.1.1 批量改状态：勾选 ids 统一设为上架/下架（双向）
 *   （Fail 边界：只支持单向）
 * - 4.1.2 批量改配送方式/分类/排序/库存：配送方式整组替换、分类改 category_id、
 *   排序统一设为 N、库存统一设为固定值（非增减）；分类须属本店且存在
 *   （Fail 边界：库存做成增减语义）
 * - 4.1.3 入参校验：ids 为空、操作类型非法、库存/门店库存为负、排序非整数、分类不存在
 *   （Fail 边界：负库存入库）
 * - 4.1.4 恒带 shopId，ids 混入他店 skuId 该 id 不更新（计入 skipped）
 *   （Fail 边界：UPDATE ... WHERE id IN (?) 漏 shopId）
 *
 * 用例分类（表格驱动，适配批量更新行为）：
 * 1. 字段级校验：各操作类型只改目标字段，值正确
 * 2. 类型与格式：value 为字符串表达的目标值，按操作类型解析（库存/排序为整数）
 * 3. 重复创建：重复对同状态批量设置幂等成功
 * 4. 非法输入：空 ids / 非法操作类型 / 负库存 / 非整数 / 他店分类 → 拒绝且 DB 零改动；
 *    混入他店 id → 该 id 零改动并计入跳过
 *
 * 测试库为 @TempDir 下的临时 SQLite 库，真实 SkuMapper/CategoryMapper XML，
 * 固定数据：店铺 1 商品 id=1/2/3（status=1、stock=7、storeStock=3、delivery=EXPRESS、sort=1、category=100）；
 * 店铺 2 商品 id=4；类目：店铺 1 的 100/101、店铺 2 的 200。
 */
class SkuBatchUpdateServiceTest {

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private SkuMapper skuMapper;
    private CategoryMapper categoryMapper;
    private SkuServiceImpl skuService;

    private final List<Integer> ownIds = Arrays.asList(1, 2, 3);
    private static final int OTHER_SHOP_SKU_ID = 4;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "product-batch-test.db").getAbsolutePath();
        dataSource = new UnpooledDataSource("org.sqlite.JDBC", jdbcUrl, null, null);

        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        for (String xml : Arrays.asList("mapper/SkuMapper.xml", "mapper/CategoryMapper.xml")) {
            try (InputStream in = Resources.getResourceAsStream(xml)) {
                new XMLMapperBuilder(in, configuration, xml, configuration.getSqlFragments()).parse();
            }
        }
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);

        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS tb_sku");
            st.executeUpdate("CREATE TABLE tb_sku ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "shop_id INTEGER NOT NULL,"
                    + "category_id INTEGER NOT NULL,"
                    + "name VARCHAR(100) NOT NULL,"
                    + "description TEXT,"
                    + "price DECIMAL(10,2) NOT NULL,"
                    + "market_price DECIMAL(10,2),"
                    + "stock INTEGER,"
                    + "stock_threshold INTEGER,"
                    + "sales INTEGER DEFAULT 0,"
                    + "fake_sales INTEGER DEFAULT 0,"
                    + "store_stock INTEGER DEFAULT 0,"
                    + "type TINYINT NOT NULL,"
                    + "product_type TINYINT,"
                    + "images VARCHAR(1000),"
                    + "spec VARCHAR(500),"
                    + "delivery_methods VARCHAR(100),"
                    + "sort INTEGER DEFAULT 0,"
                    + "status TINYINT DEFAULT 0,"
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
            st.executeUpdate("DROP TABLE IF EXISTS tb_category");
            st.executeUpdate("CREATE TABLE tb_category ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "shop_id INTEGER NOT NULL,"
                    + "name VARCHAR(100) NOT NULL,"
                    + "parent_id INTEGER DEFAULT 0,"
                    + "sort INTEGER DEFAULT 0,"
                    + "status TINYINT DEFAULT 1,"
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
        }

        insertCategory(100, 1, "男装");
        insertCategory(101, 1, "女装");
        insertCategory(200, 2, "数码");

        insertSku(1, 1, "商品A");
        insertSku(2, 1, "商品B");
        insertSku(3, 1, "商品C");
        insertSku(OTHER_SHOP_SKU_ID, 2, "他店商品");

        session = sqlSessionFactory.openSession(true);
        skuMapper = session.getMapper(SkuMapper.class);
        categoryMapper = session.getMapper(CategoryMapper.class);

        skuService = new SkuServiceImpl();
        ReflectionTestUtils.setField(skuService, "skuMapper", skuMapper);
        ReflectionTestUtils.setField(skuService, "categoryMapper", categoryMapper);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private void insertCategory(int id, int shopId, String name) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_category (id, shop_id, name, parent_id, sort, status) VALUES (?,?,?,?,?,?)")) {
            ps.setInt(1, id);
            ps.setInt(2, shopId);
            ps.setString(3, name);
            ps.setInt(4, 0);
            ps.setInt(5, 1);
            ps.setInt(6, 1);
            ps.executeUpdate();
        }
    }

    /** 固定初始态：status=1、stock=7、storeStock=3、delivery=EXPRESS、sort=1 */
    private void insertSku(int id, int shopId, String name) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_sku (id, shop_id, category_id, name, price, stock, store_stock,"
                             + " type, product_type, delivery_methods, sort, status)"
                             + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setInt(1, id);
            ps.setInt(2, shopId);
            ps.setInt(3, shopId == 1 ? 100 : 200);
            ps.setString(4, name);
            ps.setBigDecimal(5, new BigDecimal("10.00"));
            ps.setInt(6, 7);
            ps.setInt(7, 3);
            ps.setInt(8, 1);
            ps.setInt(9, 1);
            ps.setString(10, "EXPRESS");
            ps.setInt(11, 1);
            ps.setInt(12, 1);
            ps.executeUpdate();
        }
    }

    private SkuPO read(int id) {
        return skuMapper.selectById(id);
    }

    private List<SkuPO> snapshot() {
        List<SkuPO> all = new ArrayList<>();
        for (int id : new int[]{1, 2, 3, OTHER_SHOP_SKU_ID}) {
            all.add(read(id));
        }
        return all;
    }

    private SkuPO otherShopSku() {
        return read(OTHER_SHOP_SKU_ID);
    }

    private void assertOwnSkus(Consumer<SkuPO> checker) {
        for (Integer id : ownIds) {
            checker.accept(read(id));
        }
    }

    // ============ 4.1.1 + 4.1.2 字段级校验：按操作类型分发，只改目标字段（表格驱动） ============

    /**
     * 列：用例名、操作类型 action、目标值 value、对每个本店商品的断言
     */
    static Stream<Arguments> fieldUpdateCases() {
        return Stream.of(
                // ----1. 字段级校验：各操作类型只改目标字段且值正确----
                arguments("批量下架", "status", "0",
                        (Consumer<SkuPO>) po -> assertEquals(0, po.getStatus().intValue(), "status 应设为 0")),
                arguments("批量上架", "status", "1",
                        (Consumer<SkuPO>) po -> assertEquals(1, po.getStatus().intValue(), "status 应设为 1")),
                arguments("配送方式整组替换", "deliveryMethods", "CITY,PICKUP",
                        (Consumer<SkuPO>) po -> assertEquals("CITY,PICKUP", po.getDeliveryMethods(),
                                "配送方式应整组替换")),
                arguments("分类改为本店类目", "categoryId", "101",
                        (Consumer<SkuPO>) po -> assertEquals(101, po.getCategoryId().intValue(),
                                "category_id 应改为 101")),
                arguments("排序统一设为9", "sort", "9",
                        (Consumer<SkuPO>) po -> assertEquals(9, po.getSort().intValue(), "sort 应统一设为 9")),

                // ----2. 类型与格式：value 为字符串目标值，库存/门店库存统一设为固定值（非增减）----
                arguments("仓库库存设为固定值5而非累加", "stock", "5",
                        (Consumer<SkuPO>) po -> assertEquals(5, po.getStock().intValue(),
                                "库存 7 应被设为 5（非 7+5=12）")),
                arguments("门店库存设为固定值2而非累加", "storeStock", "2",
                        (Consumer<SkuPO>) po -> assertEquals(2, po.getStoreStock().intValue(),
                                "门店库存 3 应被设为 2（非 3+2=5）"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fieldUpdateCases")
    void 批量更新_按操作类型分发_本店商品一致生效且他店零改动(String caseName, String action, String value,
                                                  Consumer<SkuPO> checker) {
        SkuPO otherBefore = otherShopSku();

        BatchUpdateResult result = skuService.batchUpdate(1, ownIds, action, value);

        assertEquals(3, result.getAffected(), caseName + "：本店 3 个商品应全部生效");
        assertEquals(0, result.getSkipped(), caseName + "：无他店 id 时不应有跳过");
        assertOwnSkus(checker);
        assertEquals(otherBefore, otherShopSku(), caseName + "：他店商品必须零改动");
    }

    @Test
    void 批量状态切换_下架后再上架_双向生效() {
        skuService.batchUpdate(1, ownIds, "status", "0");
        assertOwnSkus(po -> assertEquals(0, po.getStatus().intValue(), "批量下架后 status 应为 0"));

        skuService.batchUpdate(1, ownIds, "status", "1");
        assertOwnSkus(po -> assertEquals(1, po.getStatus().intValue(), "批量上架后 status 应为 1"));
    }

    // ============ 3. 重复创建：重复对同状态批量设置幂等成功 ============

    @Test
    void 重复批量下架_幂等成功() {
        BatchUpdateResult first = skuService.batchUpdate(1, ownIds, "status", "0");
        BatchUpdateResult second = skuService.batchUpdate(1, ownIds, "status", "0");

        assertEquals(3, first.getAffected(), "首次批量下架应生效 3 条");
        assertEquals(3, second.getAffected(), "重复批量下架仍应幂等生效 3 条");
        assertOwnSkus(po -> assertEquals(0, po.getStatus().intValue(), "重复设置后 status 仍为 0"));
    }

    // ============ 4. 非法输入：一律拒绝（中文原因）且 DB 零改动（表格驱动） ============

    /**
     * 列：用例名、ids、action、value、期望中文消息片段
     */
    static Stream<Arguments> illegalCases() {
        return Stream.of(
                arguments("ids为空", Collections.<Integer>emptyList(), "status", "0", "商品"),
                arguments("ids为null", null, "status", "0", "商品"),
                arguments("操作类型非法", Arrays.asList(1, 2, 3), "unknownAction", "0", "操作类型"),
                arguments("操作类型为空", Arrays.asList(1, 2, 3), null, "0", "操作类型"),
                arguments("仓库库存为负", Arrays.asList(1, 2, 3), "stock", "-1", "库存"),
                arguments("门店库存为负", Arrays.asList(1, 2, 3), "storeStock", "-5", "库存"),
                arguments("仓库库存非整数", Arrays.asList(1, 2, 3), "stock", "abc", "库存"),
                arguments("排序非整数", Arrays.asList(1, 2, 3), "sort", "abc", "排序"),
                arguments("分类不存在", Arrays.asList(1, 2, 3), "categoryId", "9999", "分类"),
                arguments("分类属于他店", Arrays.asList(1, 2, 3), "categoryId", "200", "分类")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("illegalCases")
    void 非法入参_拒绝且数据库零改动(String caseName, List<Integer> ids, String action, String value,
                                  String messageFragment) {
        List<SkuPO> before = snapshot();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> skuService.batchUpdate(1, ids, action, value),
                caseName + "：非法入参必须拒绝");

        assertTrue(ex.getMessage() != null && ex.getMessage().contains(messageFragment),
                caseName + "：错误消息应含「" + messageFragment + "」，实际：" + ex.getMessage());
        assertEquals(before, snapshot(), caseName + "：拒绝后数据库必须零改动");
    }

    // ============ 4.1.4 shopId 隔离：混入他店 id 不计生效 ============

    @Test
    void 混入他店id_仅本店生效且计入跳过() {
        SkuPO otherBefore = otherShopSku();

        BatchUpdateResult result = skuService.batchUpdate(1, Arrays.asList(1, 2, OTHER_SHOP_SKU_ID), "status", "0");

        assertEquals(2, result.getAffected(), "仅本店 2 个商品应生效");
        assertEquals(1, result.getSkipped(), "他店 1 个 id 应计入跳过");
        assertEquals(0, read(1).getStatus().intValue(), "本店商品 1 应已下架");
        assertEquals(0, read(2).getStatus().intValue(), "本店商品 2 应已下架");
        assertEquals(otherBefore, otherShopSku(), "他店商品必须零改动");
    }

    @Test
    void 全部为他店id_零生效全部跳过() {
        SkuPO otherBefore = otherShopSku();

        BatchUpdateResult result = skuService.batchUpdate(1, Collections.singletonList(OTHER_SHOP_SKU_ID),
                "status", "0");

        assertEquals(0, result.getAffected(), "他店 id 不得生效");
        assertEquals(1, result.getSkipped(), "他店 id 应全部计入跳过");
        assertEquals(otherBefore, otherShopSku(), "他店商品必须零改动");
    }
}
