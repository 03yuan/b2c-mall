package com.ysh.b2cmall.product.dao.mapper;

import com.ysh.b2cmall.product.dao.po.SkuPO;
import com.ysh.b2cmall.product.dao.po.SkuQuery;
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
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 商品列表动态条件分页查询测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-product-list.md 功能上下文 3.1（DAO 层 - SkuMapper + XML 动态 SQL）：
 * - 3.1.1 动态条件分页查询：shopId 固定；name LIKE 模糊（%%）；product_type、type、status、category_id 等值；
 *   delivery_methods 多选（命中任一所选即返回）
 *   （Fail 边界：LIKE 不带 % 变精确匹配；多选写成 AND（须同时支持全部配送方式）→ 结果缺失）
 * - 3.1.2 排序规则：sort DESC（数字越大越靠前），同 sort 按 id DESC 稳定排序
 *   （Fail 边界：默认按 id 排 → 排序字段失效）
 * - 3.1.3 分页边界：page≥1、size 限 1~100；超页返回空列表；total=筛选命中总数
 *   （Fail 边界：size=0/负数 → SQL 异常 500；total 不随筛选变化）
 *
 * 用例分类（表格驱动，固定数据 10 条本店 + 1 条他店）：
 * 1. 字段级校验：每个筛选字段各自命中正确子集（shopId/name/productType/type/status/categoryId/delivery）
 * 2. 类型与格式：name 裸关键词自动模糊（前缀/中段/后缀子串）、delivery 多选 OR（非 AND）、
 *    sort DESC + id DESC 排序格式、组合条件叠加、size 边界值 1/100 合法
 * 3. 重复创建：同条件重复查询结果与 total 一致（幂等）；同 sort 翻页不重复不漏行（稳定排序）
 * 4. 非法输入：page/size 越界直接 IllegalArgumentException（而非 SQL 异常 500）；
 *    超页返回空列表；无命中筛选一律空列表 + total=0；他店数据永不出现
 *
 * 固定数据（shop 1 本店 10 条 + shop 2 他店 1 条）：
 * id/name/productType/type/status/categoryId/delivery/sort
 * 11 苹果手机壳 1 1 1 100 CITY,EXPRESS 5      16 牛奶布丁 1 1 1 300 PICKUP 2
 * 12 苹果耳机   2 1 1 100 EXPRESS      5      17 手机支架 1 1 0 100 EXPRESS 4
 * 13 香蕉牛奶   1 2 0 200 PICKUP       3      18 耳机收纳包 2 2 1 200 CITY,EXPRESS 1
 * 14 果汁奶昔   1 1 0 300 CITY,PICKUP  4      19 苹果橙汁 1 1 1 300 EXPRESS 3
 * 15 橙子果篮   2 3 1 200 CITY         3      20 卡券礼包 1 3 1 100 PICKUP 2
 * 99 苹果他店商品 1 1 1 100 CITY,EXPRESS 99（shop 2，sort=99 为泄漏绊线：一旦漏 shop_id 必排最前）
 */
class SkuListQueryTest {

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private SkuMapper skuMapper;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "product-list-query-test.db").getAbsolutePath();
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
        }

        session = sqlSessionFactory.openSession(true);
        skuMapper = session.getMapper(SkuMapper.class);

        // 本店（shop 1）10 条
        insertSku(11, 1, "苹果手机壳", 1, 1, 1, 100, "CITY,EXPRESS", 5);
        insertSku(12, 1, "苹果耳机", 2, 1, 1, 100, "EXPRESS", 5);
        insertSku(13, 1, "香蕉牛奶", 1, 2, 0, 200, "PICKUP", 3);
        insertSku(14, 1, "果汁奶昔", 1, 1, 0, 300, "CITY,PICKUP", 4);
        insertSku(15, 1, "橙子果篮", 2, 3, 1, 200, "CITY", 3);
        insertSku(16, 1, "牛奶布丁", 1, 1, 1, 300, "PICKUP", 2);
        insertSku(17, 1, "手机支架", 1, 1, 0, 100, "EXPRESS", 4);
        insertSku(18, 1, "耳机收纳包", 2, 2, 1, 200, "CITY,EXPRESS", 1);
        insertSku(19, 1, "苹果橙汁", 1, 1, 1, 300, "EXPRESS", 3);
        insertSku(20, 1, "卡券礼包", 1, 3, 1, 100, "PICKUP", 2);
        // 他店（shop 2）1 条：名称同样命中“苹果”、sort=99 最大，作越权泄漏绊线
        insertSku(99, 2, "苹果他店商品", 1, 1, 1, 100, "CITY,EXPRESS", 99);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private void insertSku(int id, int shopId, String name, int productType, int type,
                           int status, int categoryId, String deliveryMethods, int sort) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_sku (id, shop_id, category_id, name, price, type, product_type,"
                             + " status, delivery_methods, sort) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setInt(1, id);
            ps.setInt(2, shopId);
            ps.setInt(3, categoryId);
            ps.setString(4, name);
            ps.setBigDecimal(5, new BigDecimal("9.90"));
            ps.setInt(6, type);
            ps.setInt(7, productType);
            ps.setInt(8, status);
            ps.setString(9, deliveryMethods);
            ps.setInt(10, sort);
            ps.executeUpdate();
        }
    }

    /** 登录店铺 1 的基础查询（默认合法分页参数） */
    private SkuQuery query() {
        SkuQuery q = new SkuQuery();
        q.setShopId(1);
        q.setPage(1);
        q.setSize(100);
        return q;
    }

    /** 按返回顺序取 id */
    private List<Integer> ids(List<SkuPO> rows) {
        return rows.stream().map(SkuPO::getId).collect(Collectors.toList());
    }

    /** 升序排序后取 id（字段级校验只看命中集合，与排序正交） */
    private List<Integer> idsSorted(List<SkuPO> rows) {
        return rows.stream().map(SkuPO::getId).sorted().collect(Collectors.toList());
    }

    // ============ 1. 字段级校验 + 2. 类型与格式 + 4. 非法输入（筛选维度） ============

    /**
     * 列：用例名、筛选条件构造、期望命中 id 集合（升序）
     */
    static Stream<Arguments> filterCases() {
        return Stream.of(
                // ----1. 字段级校验：每个筛选字段各自命中正确子集----
                arguments("shopId字段-他店查询只回他店行", (Consumer<SkuQuery>) q -> q.setShopId(2),
                        Collections.singletonList(99)),
                arguments("name字段-裸关键词模糊命中", (Consumer<SkuQuery>) q -> q.setName("苹果"),
                        Arrays.asList(11, 12, 19)),
                arguments("productType字段-普通商品", (Consumer<SkuQuery>) q -> q.setProductType(1),
                        Arrays.asList(11, 13, 14, 16, 17, 19, 20)),
                arguments("productType字段-秒杀商品", (Consumer<SkuQuery>) q -> q.setProductType(2),
                        Arrays.asList(12, 15, 18)),
                arguments("type字段-实物", (Consumer<SkuQuery>) q -> q.setType(1),
                        Arrays.asList(11, 12, 14, 16, 17, 19)),
                arguments("type字段-虚拟", (Consumer<SkuQuery>) q -> q.setType(2),
                        Arrays.asList(13, 18)),
                arguments("type字段-卡券", (Consumer<SkuQuery>) q -> q.setType(3),
                        Arrays.asList(15, 20)),
                arguments("status字段-上架", (Consumer<SkuQuery>) q -> q.setStatus(1),
                        Arrays.asList(11, 12, 15, 16, 18, 19, 20)),
                arguments("status字段-下架", (Consumer<SkuQuery>) q -> q.setStatus(0),
                        Arrays.asList(13, 14, 17)),
                arguments("categoryId字段-类目100", (Consumer<SkuQuery>) q -> q.setCategoryId(100),
                        Arrays.asList(11, 12, 17, 20)),
                arguments("categoryId字段-类目200", (Consumer<SkuQuery>) q -> q.setCategoryId(200),
                        Arrays.asList(13, 15, 18)),
                arguments("categoryId字段-类目300", (Consumer<SkuQuery>) q -> q.setCategoryId(300),
                        Arrays.asList(14, 16, 19)),
                arguments("delivery字段-单选CITY", (Consumer<SkuQuery>) q -> q.setDeliveryMethods(Collections.singletonList("CITY")),
                        Arrays.asList(11, 14, 15, 18)),
                arguments("delivery字段-单选PICKUP", (Consumer<SkuQuery>) q -> q.setDeliveryMethods(Collections.singletonList("PICKUP")),
                        Arrays.asList(13, 14, 16, 20)),
                arguments("delivery字段-单选EXPRESS", (Consumer<SkuQuery>) q -> q.setDeliveryMethods(Collections.singletonList("EXPRESS")),
                        Arrays.asList(11, 12, 17, 18, 19)),

                // ----2. 类型与格式：name 裸关键词自动模糊（LIKE 带 %）、delivery 多选 OR（非 AND）、组合条件----
                arguments("name中段子串-果-模糊非精确", (Consumer<SkuQuery>) q -> q.setName("果"),
                        Arrays.asList(11, 12, 14, 15, 19)),
                arguments("name前缀子串-苹果手机", (Consumer<SkuQuery>) q -> q.setName("苹果手机"),
                        Collections.singletonList(11)),
                arguments("name后缀子串-耳机", (Consumer<SkuQuery>) q -> q.setName("耳机"),
                        Arrays.asList(12, 18)),
                arguments("delivery多选CITY加PICKUP-命中任一即返回", (Consumer<SkuQuery>) q -> q.setDeliveryMethods(Arrays.asList("CITY", "PICKUP")),
                        Arrays.asList(11, 13, 14, 15, 16, 18, 20)),
                arguments("delivery多选CITY加EXPRESS-命中任一即返回", (Consumer<SkuQuery>) q -> q.setDeliveryMethods(Arrays.asList("CITY", "EXPRESS")),
                        Arrays.asList(11, 12, 14, 15, 17, 18, 19)),
                arguments("组合条件-name加status", (Consumer<SkuQuery>) q -> {
                    q.setName("果");
                    q.setStatus(0);
                }, Collections.singletonList(14)),
                arguments("组合条件-categoryId加productType", (Consumer<SkuQuery>) q -> {
                    q.setCategoryId(200);
                    q.setProductType(2);
                }, Arrays.asList(15, 18)),
                arguments("组合条件-name加productType", (Consumer<SkuQuery>) q -> {
                    q.setName("苹果");
                    q.setProductType(2);
                }, Collections.singletonList(12)),

                // ----4. 非法输入：无命中筛选一律空列表 + total=0----
                arguments("name不存在-空列表", (Consumer<SkuQuery>) q -> q.setName("zzz"),
                        Collections.<Integer>emptyList()),
                arguments("categoryId不存在-空列表", (Consumer<SkuQuery>) q -> q.setCategoryId(888),
                        Collections.<Integer>emptyList()),
                arguments("delivery未知方式-空列表", (Consumer<SkuQuery>) q -> q.setDeliveryMethods(Collections.singletonList("FLY")),
                        Collections.<Integer>emptyList()),
                arguments("组合条件无交集-空列表", (Consumer<SkuQuery>) q -> {
                    q.setName("苹果");
                    q.setStatus(0);
                }, Collections.<Integer>emptyList())
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("filterCases")
    void 筛选条件_命中正确子集(String name, Consumer<SkuQuery> filter, List<Integer> expectedIds) {
        SkuQuery q = query();
        filter.accept(q);

        assertEquals(expectedIds, idsSorted(skuMapper.selectPage(q)), name + "：筛选命中子集不符");
        assertEquals(expectedIds.size(), skuMapper.countByFilter(q),
                name + "：total 必须等于筛选命中总数并随筛选变化");
    }

    // ============ 2. 类型与格式（排序） + 3. 重复创建（翻页稳定） + 4. 非法输入（超页） ============

    /**
     * 列：用例名、附加筛选条件、页码、每页条数、期望 id 序列（按返回顺序）
     */
    static Stream<Arguments> pageCases() {
        Consumer<SkuQuery> none = q -> {
        };
        return Stream.of(
                // ----2. 类型与格式：sort DESC（数字越大越靠前），同 sort 按 id DESC 稳定排序----
                arguments("默认排序-sort降序同sort按id降序", none, 1, 100,
                        Arrays.asList(12, 11, 17, 14, 19, 15, 13, 20, 16, 18)),
                arguments("size下限值1合法-取首行", none, 1, 1,
                        Collections.singletonList(12)),
                arguments("size上限值100合法-全量有序", none, 1, 100,
                        Arrays.asList(12, 11, 17, 14, 19, 15, 13, 20, 16, 18)),

                // ----3. 重复创建：分页切分稳定，逐页不重复不漏行----
                arguments("每页3条-第1页", none, 1, 3, Arrays.asList(12, 11, 17)),
                arguments("每页3条-第2页", none, 2, 3, Arrays.asList(14, 19, 15)),
                arguments("每页3条-第3页", none, 3, 3, Arrays.asList(13, 20, 16)),
                arguments("每页3条-尾页不满1条", none, 4, 3, Collections.singletonList(18)),
                arguments("筛选后分页-第1页", (Consumer<SkuQuery>) q -> q.setName("苹果"), 1, 2,
                        Arrays.asList(12, 11)),
                arguments("筛选后分页-第2页", (Consumer<SkuQuery>) q -> q.setName("苹果"), 2, 2,
                        Collections.singletonList(19)),

                // ----4. 非法输入：超页返回空列表----
                arguments("每页3条-超页空列表", none, 5, 3, Collections.<Integer>emptyList()),
                arguments("筛选后超页-空列表", (Consumer<SkuQuery>) q -> q.setName("苹果"), 3, 2,
                        Collections.<Integer>emptyList())
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pageCases")
    void 排序与分页_顺序与边界(String name, Consumer<SkuQuery> filter, int page, int size,
                              List<Integer> expectedIds) {
        SkuQuery q = query();
        filter.accept(q);
        q.setPage(page);
        q.setSize(size);

        assertEquals(expectedIds, ids(skuMapper.selectPage(q)), name + "：排序/分页顺序不符");
    }

    // ============ 4. 非法输入：page/size 越界直接拒绝（Fail 边界：落进 SQL 变 500） ============

    /**
     * 列：用例名、非法分页参数设置动作（构造 SkuQuery 时即应抛 IllegalArgumentException）
     */
    static Stream<Arguments> illegalPagingCases() {
        return Stream.of(
                arguments("page为0非法", (Runnable) () -> new SkuQuery().setPage(0)),
                arguments("page为负数非法", (Runnable) () -> new SkuQuery().setPage(-1)),
                arguments("size为0非法", (Runnable) () -> new SkuQuery().setSize(0)),
                arguments("size为负数非法", (Runnable) () -> new SkuQuery().setSize(-3)),
                arguments("size超上限101非法", (Runnable) () -> new SkuQuery().setSize(101))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("illegalPagingCases")
    void 非法分页参数_直接拒绝(String name, Runnable bad) {
        assertThrows(IllegalArgumentException.class, bad::run,
                name + "：必须显式拒绝（page≥1、size 限 1~100），而非 SQL 异常 500");
    }

    // ----3. 重复创建：同条件重复查询幂等----

    @Test
    void 重复查询_同条件结果与total一致() {
        SkuQuery first = query();
        first.setName("果");
        SkuQuery second = query();
        second.setName("果");

        assertEquals(ids(skuMapper.selectPage(first)), ids(skuMapper.selectPage(second)),
                "同条件重复查询结果必须一致（幂等、无副作用）");
        assertEquals(skuMapper.countByFilter(first), skuMapper.countByFilter(second),
                "重复查询 total 必须一致");
    }

    // ----3. 重复创建：同 sort 翻页不重复不漏行（稳定排序）----

    @Test
    void 同sort翻页_不重复不漏行() {
        List<Integer> scanned = new ArrayList<>();
        for (int page = 1; page <= 10; page++) {
            SkuQuery q = query();
            q.setPage(page);
            q.setSize(1);
            scanned.addAll(ids(skuMapper.selectPage(q)));
        }

        assertEquals(Arrays.asList(12, 11, 17, 14, 19, 15, 13, 20, 16, 18), scanned,
                "逐页扫描必须不重复不漏行，且同 sort（12/11、17/14 等）跨页顺序稳定");

        SkuQuery beyond = query();
        beyond.setPage(11);
        beyond.setSize(1);
        assertTrue(skuMapper.selectPage(beyond).isEmpty(), "超出总页数必须返回空列表");
    }

    // ----4. 非法输入：他店数据永不出现（shopId 固定，Fail 边界：漏 shop_id 条件）----

    @Test
    void 他店商品永不出现() {
        SkuQuery q = query();
        List<SkuPO> rows = skuMapper.selectPage(q);

        assertEquals(10, skuMapper.countByFilter(q), "total 不得包含他店商品");
        assertFalse(rows.stream().anyMatch(r -> r.getId() == 99), "他店商品不得出现在列表中");
        assertEquals(12, rows.get(0).getId().intValue(),
                "他店商品 sort=99 最大，若泄漏必排最前，故首位必须是本店商品 12");

        SkuQuery nameQ = query();
        nameQ.setName("苹果");
        assertFalse(skuMapper.selectPage(nameQ).stream().anyMatch(r -> r.getId() == 99),
                "同名他店商品不得被模糊筛选命中");
    }
}
