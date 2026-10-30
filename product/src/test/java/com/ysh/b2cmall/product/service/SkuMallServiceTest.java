package com.ysh.b2cmall.product.service;

import com.ysh.b2cmall.product.dao.mapper.SkuMapper;
import com.ysh.b2cmall.product.dao.po.MallSkuQuery;
import com.ysh.b2cmall.product.dao.po.SkuPO;
import com.ysh.b2cmall.product.dao.po.SkuPageResult;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * C 端商品卡片列表 Service 层「全部 vs 分类过滤」语义契约测试（TDD 契约固化）。
 * 对应 .tdd/task/mall-category-product-list.md 功能上下文 4.1（Service/DAO - categoryName 过滤语义）：
 * - 4.1.1 不传 categoryName（等价「全部」Tab）-> 全平台全部上架商品（跨店聚合）
 * - 4.1.2 传 categoryName -> 只含该分类名下上架商品；他店同名分类商品同样命中
 * - 4.1.3 不存在的分类名 -> 空列表 + total=0，不抛异常（500）
 *
 * 说明：底层 SQL（selectMallPage/countMallByFilter）已在功能点 2.2 实现，Service 透传（queryMallPage）
 * 已在 2.3 实现；本测试在 Service 层端到端固化「全部 vs 过滤」互斥语义，作为回归护栏。
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：不传分类=全部上架商品（含两店）；传分类只含该分类
 * 2. 类型与格式：他店同名分类商品同样命中（跨店聚合口径）
 * 3. 重复创建：同条件重复查询结果与 total 一致（幂等）
 * 4. 非法输入：不存在分类名 -> 空集合 + total=0 且不抛异常
 *
 * 固定数据（精确对应 4.1.2 文档语义）：
 * shop1 分类「水果」(id=100)；shop2 分类「水果」(id=500)、「蔬菜」(id=600)
 * 商品（全部 status=1 上架）：
 *   1 店1水果   shop1 cat100
 *   2 店1水果2  shop1 cat100
 *   3 店2水果   shop2 cat500
 *   4 店2蔬菜   shop2 cat600
 */
class SkuMallServiceTest {

    @TempDir
    File tempDir;

    private SqlSession session;
    private SkuMapper skuMapper;
    private SkuServiceImpl skuService;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "product-mall-service-test.db").getAbsolutePath();
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
            st.executeUpdate("DROP TABLE IF EXISTS tb_category");
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

        insertCategory(dataSource, 100, 1, "水果");
        insertCategory(dataSource, 500, 2, "水果");
        insertCategory(dataSource, 600, 2, "蔬菜");

        insertSku(dataSource, 1, 1, 100, "店1水果");
        insertSku(dataSource, 2, 1, 100, "店1水果2");
        insertSku(dataSource, 3, 2, 500, "店2水果");
        insertSku(dataSource, 4, 2, 600, "店2蔬菜");

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

    private void insertCategory(UnpooledDataSource dataSource, int id, int shopId, String name) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_category (id, shop_id, name, parent_id, sort, status) VALUES (?,?,?,0,0,1)")) {
            ps.setInt(1, id);
            ps.setInt(2, shopId);
            ps.setString(3, name);
            ps.executeUpdate();
        }
    }

    private void insertSku(UnpooledDataSource dataSource, int id, int shopId, int categoryId, String name) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_sku (id, shop_id, category_id, name, price, type, status, sort) VALUES (?,?,?,?,?,1,1,?)")) {
            ps.setInt(1, id);
            ps.setInt(2, shopId);
            ps.setInt(3, categoryId);
            ps.setString(4, name);
            ps.setBigDecimal(5, new BigDecimal("9.90"));
            ps.setInt(6, id);
            ps.executeUpdate();
        }
    }

    /** 默认查询：page=1, size=100，可选 categoryName */
    private MallSkuQuery query(String categoryName) {
        MallSkuQuery q = new MallSkuQuery();
        q.setPage(1);
        q.setSize(100);
        q.setCategoryName(categoryName);
        return q;
    }

    private List<Integer> ids(List<SkuPO> rows) {
        return rows.stream().map(SkuPO::getId).sorted().collect(Collectors.toList());
    }

    // ============ 1/2. 字段级校验 + 类型与格式：全部 vs 分类过滤（跨店命中） ============

    static Stream<Arguments> filterCases() {
        return Stream.of(
                // 4.1.1 不传 categoryName = 全部上架商品（跨店聚合，含两店）
                arguments("不传分类-全平台全部上架商品", null, Arrays.asList(1, 2, 3, 4)),
                // 4.1.2 传「水果」-> 两店同名分类商品都命中（3 个），蔬菜不出现
                arguments("水果-两店同名分类商品都命中", "水果", Arrays.asList(1, 2, 3)),
                // 4.1.2 传「蔬菜」-> 只含蔬菜分类商品
                arguments("蔬菜-只含该分类商品", "蔬菜", Collections.singletonList(4))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("filterCases")
    void categoryName过滤_命中正确子集(String caseName, String categoryName, List<Integer> expectedIds) {
        SkuPageResult result = skuService.queryMallPage(query(categoryName));

        assertEquals(expectedIds, ids(result.getRows()), caseName + "：rows 命中子集不符");
        assertEquals(expectedIds.size(), result.getTotal(), caseName + "：total 必须等于命中总数");
    }

    // ============ 4. 非法输入：不存在分类名 -> 空集合 + total=0 不抛异常（4.1.3） ============

    @Test
    void 不存在分类名_空集合且total为0且不抛异常() {
        SkuPageResult result = assertDoesNotThrow(
                () -> skuService.queryMallPage(query("不存在的分类XYZ")),
                "不存在的分类名不得抛异常（500），必须正常返回空结果");

        assertTrue(result.getRows().isEmpty(), "不存在分类名必须返回空集合，不得伪造商品");
        assertEquals(0, result.getTotal(), "不存在分类名 total 必须为 0");
    }

    // ============ 3. 重复创建：同条件重复查询结果与 total 一致 ============

    @Test
    void 同条件重复查询_结果与total一致() {
        SkuPageResult first = skuService.queryMallPage(query("水果"));
        SkuPageResult second = skuService.queryMallPage(query("水果"));

        assertEquals(ids(first.getRows()), ids(second.getRows()), "同条件重复查询 rows 必须一致（幂等）");
        assertEquals(first.getTotal(), second.getTotal(), "同条件重复查询 total 必须一致");
    }

    // ============ 4.1.1 补充：不传 categoryName 确实含两店数据（跨店聚合，无 shopId 泄漏） ============

    @Test
    void 不传分类_含两店数据_无单店过滤() {
        SkuPageResult result = skuService.queryMallPage(query(null));

        assertEquals(4, result.getTotal(), "不传分类必须返回全平台全部 4 条上架商品");
        // 含 shop1（id1/2）与 shop2（id3/4）数据，验证未误带单店 shopId
        assertTrue(ids(result.getRows()).containsAll(Arrays.asList(1, 2, 3, 4)),
                "不传分类必须跨店聚合，含两店数据，不得误带单店过滤");
    }
}
