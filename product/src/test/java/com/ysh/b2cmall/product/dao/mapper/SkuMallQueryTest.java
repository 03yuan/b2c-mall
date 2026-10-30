package com.ysh.b2cmall.product.dao.mapper;

import com.ysh.b2cmall.product.dao.po.MallSkuQuery;
import com.ysh.b2cmall.product.dao.po.SkuPO;
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
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * C 端商品卡片列表 DAO 分页查询测试（TDD 红阶段）。
 * 对应 .tdd/task/mall-category-product-list.md 功能上下文 2.2（DAO 层 - SkuMapper 新增 mall 查询）：
 * - 2.2.1 查询恒强制 status=1，不接收 status 参数、不带 shop_id 条件（跨店聚合）
 *   （Fail 边界：信任前端传 status -> 可绕过只看上架边界；误加 shop_id -> C 端只出单店）
 * - 2.2.2 categoryName 可选过滤：sku.category_id IN (SELECT id FROM tb_category WHERE name=? AND status=1)
 *   （Fail 边界：按 category_id 等值 -> 只命中单店单个类目；分类 status=0 时其商品不应命中）
 * - 2.2.3 分页：total=命中总数；LIMIT/OFFSET=(page-1)*size；page/size 非法值拒绝
 *   （Fail 边界：offset 算错 -> 翻页重复/漏行；校验缺失 -> page=0 落到 SQLite LIMIT 异常）
 * - 2.2.4 空结果返回空集合 + total=0，不伪造任何数值
 *   （Fail 边界：填充演示数据/占位图 -> 用户反感虚假数据）
 *
 * 用例分类（表格驱动，固定数据两店同名分类商品）：
 * 1. 字段级校验：只返回上架商品、跨店聚合（含两店数据）、categoryName 跨店命中
 * 2. 类型与格式：status 恒强制为 1（下架商品不出现）；分页 offset 正确
 * 3. 重复创建：同条件重复查询结果与 total 一致（幂等）
 * 4. 非法输入：page/size 越界拒绝；不存在分类名 -> 空列表 total=0；分类 status=0 不命中
 *
 * 固定数据：
 * shop1 分类「水果」(id=100,status=1)、「蔬菜」(id=200,status=1)、「海鲜」(id=300,status=0)
 * shop2 分类「水果」(id=500,status=1)、「蔬菜」(id=600,status=1)
 * 商品（id/name/shop/category/status/sort）：
 *   1 店1苹果  1 100 上架 sort=1     5 店1香蕉 1 100 下架
 *   2 店1菠菜  1 200 上架 sort=2     6 店1龙虾 1 300 上架（分类禁用）
 *   3 店2苹果  2 500 上架 sort=3     7 店2菠菜 2 600 下架
 *   4 店2苹果2 2 500 上架 sort=4
 */
class SkuMallQueryTest {

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private SkuMapper skuMapper;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "product-mall-query-test.db").getAbsolutePath();
        dataSource = new UnpooledDataSource("org.sqlite.JDBC", jdbcUrl, null, null);

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

        // 分类
        insertCategory(100, 1, "水果", 1);
        insertCategory(200, 1, "蔬菜", 1);
        insertCategory(300, 1, "海鲜", 0);
        insertCategory(500, 2, "水果", 1);
        insertCategory(600, 2, "蔬菜", 1);

        // 商品（id/name/shop/category/status/sort）
        insertSku(1, "店1苹果", 1, 100, 1, 1);
        insertSku(2, "店1菠菜", 1, 200, 1, 2);
        insertSku(3, "店2苹果", 2, 500, 1, 3);
        insertSku(4, "店2苹果2", 2, 500, 1, 4);
        insertSku(5, "店1香蕉", 1, 100, 0, 5);
        insertSku(6, "店1龙虾", 1, 300, 1, 6);
        insertSku(7, "店2菠菜", 2, 600, 0, 7);

        session = sqlSessionFactory.openSession(true);
        skuMapper = session.getMapper(SkuMapper.class);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private void insertCategory(int id, int shopId, String name, int status) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_category (id, shop_id, name, parent_id, sort, status) VALUES (?,?,?,0,0,?)")) {
            ps.setInt(1, id);
            ps.setInt(2, shopId);
            ps.setString(3, name);
            ps.setInt(4, status);
            ps.executeUpdate();
        }
    }

    private void insertSku(int id, String name, int shopId, int categoryId, int status, int sort) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_sku (id, shop_id, category_id, name, price, type, status, sort) VALUES (?,?,?,?,?,1,?,?)")) {
            ps.setInt(1, id);
            ps.setInt(2, shopId);
            ps.setInt(3, categoryId);
            ps.setString(4, name);
            ps.setBigDecimal(5, new BigDecimal("9.90"));
            ps.setInt(6, status);
            ps.setInt(7, sort);
            ps.executeUpdate();
        }
    }

    /** 默认合法查询（page=1, size=100） */
    private MallSkuQuery query() {
        MallSkuQuery q = new MallSkuQuery();
        q.setPage(1);
        q.setSize(100);
        return q;
    }

    private List<Integer> ids(List<SkuPO> rows) {
        return rows.stream().map(SkuPO::getId).collect(Collectors.toList());
    }

    private List<Integer> idsSorted(List<SkuPO> rows) {
        return rows.stream().map(SkuPO::getId).sorted().collect(Collectors.toList());
    }

    // ============ 1. 字段级校验 + 2. 类型与格式 + 4. 非法输入（categoryName 过滤维度） ============

    /**
     * 列：用例名、categoryName、期望命中 id 集合（升序，只看命中集合）
     */
    static Stream<Arguments> categoryFilterCases() {
        return Stream.of(
                // ----1. 字段级校验：不传 categoryName = 全部上架商品（跨店聚合，含两店）----
                arguments("不传分类-全部上架商品跨店聚合", null,
                        Arrays.asList(1, 2, 3, 4, 6)),
                // ----1/2. categoryName 跨店命中（两店同名「水果」商品都返回）----
                arguments("水果-两店同名分类商品都命中", "水果",
                        Arrays.asList(1, 3, 4)),
                arguments("蔬菜-跨店命中", "蔬菜",
                        Collections.singletonList(2)),
                // ----4. 非法输入：不存在分类名 -> 空列表----
                arguments("不存在分类名-空列表", "不存在的分类XYZ",
                        Collections.<Integer>emptyList()),
                // ----4. 非法输入：禁用分类（海鲜 status=0）其商品不命中----
                arguments("禁用分类-其商品不命中", "海鲜",
                        Collections.<Integer>emptyList())
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("categoryFilterCases")
    void categoryName过滤_命中正确子集(String caseName, String categoryName, List<Integer> expectedIds) {
        MallSkuQuery q = query();
        q.setCategoryName(categoryName);

        assertEquals(expectedIds, idsSorted(skuMapper.selectMallPage(q)),
                caseName + "：categoryName 过滤命中子集不符");
        assertEquals(expectedIds.size(), skuMapper.countMallByFilter(q),
                caseName + "：total 必须等于命中总数");
    }

    // ============ 2. 类型与格式：恒强制 status=1（下架商品不出现） ============

    @Test
    void 下架商品恒不出现_不接收status参数() {
        List<SkuPO> rows = skuMapper.selectMallPage(query());

        List<Integer> allIds = ids(rows);
        assertFalse(allIds.contains(5), "下架商品 id=5 不得出现（恒强制 status=1）");
        assertFalse(allIds.contains(7), "下架商品 id=7 不得出现（恒强制 status=1）");
        assertEquals(5, skuMapper.countMallByFilter(query()), "total 只应统计上架商品（5 条）");
    }

    // ============ 2. 类型与格式 + 3. 重复创建：分页 offset 正确、逐页不重复不漏行 ============

    /**
     * 列：用例名、page、size、期望 id 序列（按返回顺序，默认 sort DESC, id DESC）
     * 上架商品 sort：1=1, 2=2, 3=3, 4=4, 6=6 -> sort DESC 顺序 6,4,3,2,1
     */
    static Stream<Arguments> pageCases() {
        return Stream.of(
                arguments("第1页3条", 1, 3, Arrays.asList(6, 4, 3)),
                arguments("第2页2条", 2, 3, Arrays.asList(2, 1)),
                arguments("超页空列表", 3, 3, Collections.<Integer>emptyList()),
                arguments("size=1首行", 1, 1, Collections.singletonList(6))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pageCases")
    void 分页_offset正确逐页不重复不漏行(String caseName, int page, int size, List<Integer> expectedIds) {
        MallSkuQuery q = query();
        q.setPage(page);
        q.setSize(size);

        assertEquals(expectedIds, ids(skuMapper.selectMallPage(q)), caseName + "：分页顺序不符");
    }

    @Test
    void 逐页扫描不重复不漏行() {
        List<Integer> scanned = new ArrayList<>();
        for (int page = 1; page <= 5; page++) {
            MallSkuQuery q = query();
            q.setPage(page);
            q.setSize(1);
            scanned.addAll(ids(skuMapper.selectMallPage(q)));
        }
        assertEquals(Arrays.asList(6, 4, 3, 2, 1), scanned, "逐页扫描必须不重复不漏行（稳定排序）");
    }

    // ============ 4. 非法输入：page/size 越界直接拒绝 ============

    static Stream<Arguments> illegalPagingCases() {
        return Stream.of(
                arguments("page=0非法", (Runnable) () -> new MallSkuQuery().setPage(0)),
                arguments("page=负数非法", (Runnable) () -> new MallSkuQuery().setPage(-1)),
                arguments("size=0非法", (Runnable) () -> new MallSkuQuery().setSize(0)),
                arguments("size=101非法", (Runnable) () -> new MallSkuQuery().setSize(101)),
                arguments("size=负数非法", (Runnable) () -> new MallSkuQuery().setSize(-5))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("illegalPagingCases")
    void 非法分页参数_直接拒绝(String caseName, Runnable bad) {
        assertThrows(IllegalArgumentException.class, bad::run,
                caseName + "：必须显式拒绝（page≥1、size 限 1~100），而非 SQL 异常 500");
    }

    // ============ 3. 重复创建：同条件重复查询结果与 total 一致 ============

    @Test
    void 重复查询_同条件结果与total一致() {
        MallSkuQuery first = query();
        first.setCategoryName("水果");
        MallSkuQuery second = query();
        second.setCategoryName("水果");

        assertEquals(ids(skuMapper.selectMallPage(first)), ids(skuMapper.selectMallPage(second)),
                "同条件重复查询结果必须一致（幂等、无副作用）");
        assertEquals(skuMapper.countMallByFilter(first), skuMapper.countMallByFilter(second),
                "重复查询 total 必须一致");
    }

    // ============ 2.2.4 空结果：空集合 + total=0，不伪造 ============

    @Test
    void 空结果_空集合且total为0() {
        MallSkuQuery q = query();
        q.setCategoryName("不存在的分类XYZ");

        List<SkuPO> rows = skuMapper.selectMallPage(q);
        assertTrue(rows.isEmpty(), "无命中必须返回空集合，不得伪造商品");
        assertEquals(0, skuMapper.countMallByFilter(q), "无命中 total 必须为 0");
    }
}
