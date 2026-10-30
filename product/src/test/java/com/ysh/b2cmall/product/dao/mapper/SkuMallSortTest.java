package com.ysh.b2cmall.product.dao.mapper;

import com.ysh.b2cmall.product.dao.po.MallSkuQuery;
import com.ysh.b2cmall.product.dao.po.MallSortType;
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
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * C 端商品卡片列表 DAO 动态排序测试（TDD 红阶段）。
 * 对应 .tdd/task/mall-category-product-list.md 功能上下文 3.1（DAO 层 - 动态 ORDER BY 片段）：
 * - 3.1.1 COMPREHENSIVE（默认）= sort DESC, id DESC（同 sort 内 id 降序稳定）
 * - 3.1.2 SALES = (sales + COALESCE(fake_sales,0)) DESC, id DESC（真实 + 注水口径）
 * - 3.1.3 PRICE_ASC = price ASC, id DESC；PRICE_DESC = price DESC, id DESC（BigDecimal 数值排序非字符串）
 * - 3.1.4 ORDER BY 只能四种枚举白名单内拼接（枚举是编译期白名单，杜绝 SQL 注入）
 *
 * 用例分类（表格驱动，固定 6 条上架商品，字段各维度有区分度）：
 * 1. 字段级校验：各排序模式下返回顺序与语义精确一致
 * 2. 类型与格式：同 sort 内 id 降序稳定；同 price 内 id 降序稳定；fake_sales=null 按 0 计
 * 3. 重复创建：同排序重复查询结果一致（幂等）
 * 4. 非法输入：枚举白名单封闭（仅 4 值、SQL 片段预定义无注入面）
 *
 * 固定数据（id/sort/sales/fake_sales/price，全部 status=1、type=1）：
 *   1  1  10  5     9.90   前台销量 15
 *   2  2  12  null  100    前台销量 12（fake null 按 0）
 *   3  3  1   100   99     前台销量 101
 *   4  4  0   0     88     前台销量 0
 *   5  2  50  0     9.90   前台销量 50
 *   6  4  8   2     5.00   前台销量 10
 *
 * 期望顺序：
 *   COMPREHENSIVE（sort DESC,id DESC）: 6,4,3,5,2,1（sort=4→id6,4；sort=3→3；sort=2→5,2；sort=1→1）
 *   SALES（前台销量 DESC,id DESC）:     3(101),5(50),1(15),2(12),6(10),4(0)
 *   PRICE_ASC（price ASC,id DESC）:     6(5.00),5(9.90),1(9.90),4(88),3(99),2(100)
 *   PRICE_DESC（price DESC,id DESC）:   2(100),3(99),4(88),5(9.90),1(9.90),6(5.00)
 */
class SkuMallSortTest {

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private SkuMapper skuMapper;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "product-mall-sort-test.db").getAbsolutePath();
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

        // 单店固定 6 条上架商品（排序语义测试聚焦字段区分度，跨店已由 SkuMallQueryTest 覆盖）
        insertSku(1, 1, 10, 5, "9.90");
        insertSku(2, 2, 12, null, "100.00");
        insertSku(3, 3, 1, 100, "99.00");
        insertSku(4, 4, 0, 0, "88.00");
        insertSku(5, 2, 50, 0, "9.90");
        insertSku(6, 4, 8, 2, "5.00");

        session = sqlSessionFactory.openSession(true);
        skuMapper = session.getMapper(SkuMapper.class);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private void insertSku(int id, int sort, int sales, Integer fakeSales, String price) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_sku (id, shop_id, category_id, name, price, sales, fake_sales, type, status, sort) "
                             + "VALUES (?,1,1,?,?,?,?,1,1,?)")) {
            ps.setInt(1, id);
            ps.setString(2, "商品" + id);
            ps.setBigDecimal(3, new BigDecimal(price));
            ps.setInt(4, sales);
            if (fakeSales == null) {
                ps.setNull(5, java.sql.Types.INTEGER);
            } else {
                ps.setInt(5, fakeSales);
            }
            ps.setInt(6, sort);
            ps.executeUpdate();
        }
    }

    /** 默认合法查询：page=1, size=100，指定排序 */
    private MallSkuQuery query(MallSortType sortBy) {
        MallSkuQuery q = new MallSkuQuery();
        q.setPage(1);
        q.setSize(100);
        q.setSortBy(sortBy);
        return q;
    }

    private List<Integer> ids(List<SkuPO> rows) {
        return rows.stream().map(SkuPO::getId).collect(Collectors.toList());
    }

    // ============ 1. 字段级校验：各排序模式顺序精确一致 ============

    static Stream<Arguments> sortCases() {
        return Stream.of(
                arguments("综合排序_sort降序_id降序", MallSortType.COMPREHENSIVE,
                        Arrays.asList(6, 4, 3, 5, 2, 1)),
                arguments("销量降序_按前台销量合成值", MallSortType.SALES,
                        Arrays.asList(3, 5, 1, 2, 6, 4)),
                arguments("价格升序_数值排序", MallSortType.PRICE_ASC,
                        Arrays.asList(6, 5, 1, 4, 3, 2)),
                arguments("价格降序_数值排序", MallSortType.PRICE_DESC,
                        Arrays.asList(2, 3, 4, 5, 1, 6))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sortCases")
    void 排序模式_顺序精确一致(String caseName, MallSortType sortBy, List<Integer> expectedIds) {
        assertEquals(expectedIds, ids(skuMapper.selectMallPage(query(sortBy))),
                caseName + "：排序结果不符（ORDER BY 未采用 sortBy）");
    }

    // ============ 2. 类型与格式：同 sort / 同 price 内 id 降序稳定；fake_sales=null 按 0 ============

    @Test
    void 同sort内_id降序稳定() {
        // sort=2 有 id2、id5；sort=4 有 id4、id6 —— 综合排序下同 sort 内 id 降序
        assertEquals(Arrays.asList(6, 4, 3, 5, 2, 1), ids(skuMapper.selectMallPage(query(MallSortType.COMPREHENSIVE))),
                "同 sort 内必须按 id 降序（新商品在前），保证翻页稳定不漏行");
    }

    @Test
    void 同price内_id降序稳定() {
        // price=9.90 有 id1、id5 —— 价格升序下同价按 id 降序
        assertEquals(Arrays.asList(6, 5, 1, 4, 3, 2), ids(skuMapper.selectMallPage(query(MallSortType.PRICE_ASC))),
                "同价格内必须按 id 降序稳定");
    }

    @Test
    void 销量排序_fakeSales为null按0计() {
        // id2: sales=12, fake=null -> 前台销量 12（而非 null 导致排序错乱）
        List<Integer> order = ids(skuMapper.selectMallPage(query(MallSortType.SALES)));
        assertEquals(Integer.valueOf(3), order.get(0), "销量榜第一名应为前台销量 101 的 id3");
        // id2 前台销量 12，应排在 id6（10）之前、id1（15）之后
        assertTrue(order.indexOf(2) < order.indexOf(6), "id2(fake=null 前台12) 应排在 id6(前台10) 之前");
        assertTrue(order.indexOf(1) < order.indexOf(2), "id1(前台15) 应排在 id2(前台12) 之前");
    }

    @Test
    void 价格排序_数值非字符串() {
        // 字符串排序时 '100' < '99'（字典序），数值排序 100 > 99；断言 99 在 100 之前（升序）
        List<Integer> asc = ids(skuMapper.selectMallPage(query(MallSortType.PRICE_ASC)));
        assertTrue(asc.indexOf(3) < asc.indexOf(2),
                "价格升序 99 必须在 100 之前（数值排序，非字符串字典序）");
        List<Integer> desc = ids(skuMapper.selectMallPage(query(MallSortType.PRICE_DESC)));
        assertTrue(desc.indexOf(2) < desc.indexOf(3),
                "价格降序 100 必须在 99 之前（数值排序）");
    }

    // ============ 3. 重复创建：同排序重复查询结果一致 ============

    @Test
    void 重复查询_同排序结果一致() {
        assertEquals(ids(skuMapper.selectMallPage(query(MallSortType.SALES))),
                ids(skuMapper.selectMallPage(query(MallSortType.SALES))),
                "同排序重复查询结果必须一致（幂等）");
    }

    // ============ 4. 非法输入：枚举白名单封闭（杜绝 SQL 注入） ============

    @Test
    void 排序枚举_白名单封闭仅四值() {
        MallSortType[] values = MallSortType.values();
        assertEquals(4, values.length, "排序枚举必须恰好四种（COMPREHENSIVE/SALES/PRICE_ASC/PRICE_DESC）");
    }

    @Test
    void 排序枚举_SQL片段预定义无注入面() {
        for (MallSortType t : MallSortType.values()) {
            String sql = t.getSql();
            assertFalse(sql.contains(";"), "排序片段不得含分号（防多语句注入）：" + t);
            assertFalse(sql.contains("--"), "排序片段不得含注释符（防注入）：" + t);
            assertFalse(sql.contains("${"), "排序片段不得含二次占位符（防注入）：" + t);
            // 白名单片段只允许出现在枚举预定义集合内
            assertTrue(Arrays.asList("sort DESC, id DESC",
                    "(sales + COALESCE(fake_sales, 0)) DESC, id DESC",
                    "price ASC, id DESC",
                    "price DESC, id DESC").contains(sql),
                    "排序片段必须为预定义白名单值，实际：" + sql);
        }
    }
}
