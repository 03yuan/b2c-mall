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
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * C 端商品卡片列表 DAO 前台销量计算列测试（TDD 红阶段）。
 * 对应 .tdd/task/mall-category-product-list.md 功能上下文 5.1（DAO/PO - 前台销量计算列）：
 * - 5.1.1 mall 查询 SELECT 增加计算列 (sales + COALESCE(fake_sales, 0)) AS display_sales，
 *   SkuPO 增加只读 displaySales 属性映射；与 3.1.2 排序表达式同源。
 *   （Fail 边界：VO 层单算而排序层另算 -> 口径漂移，销量榜第一名与卡片销量数字对不上）
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：displaySales = sales + COALESCE(fake_sales,0) 精确合成
 * 2. 类型与格式：fake_sales=null 按 0 计（displaySales 非 null）；displaySales 为 Integer
 * 3. 重复创建：同查询重复调用 displaySales 一致（幂等）
 * 4. 非法输入：sales=0 且 fake=null -> displaySales=0（非 null）
 *
 * 固定数据（id/sales/fake_sales -> 期望 displaySales）：
 *   1  10  5    -> 15
 *   2  12  null -> 12（fake null 按 0）
 *   3  0   null -> 0
 */
class SkuMallDisplaySalesTest {

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private SkuMapper skuMapper;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "product-mall-display-sales-test.db").getAbsolutePath();
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

        insertSku(1, 10, 5);
        insertSku(2, 12, null);
        insertSku(3, 0, null);

        session = sqlSessionFactory.openSession(true);
        skuMapper = session.getMapper(SkuMapper.class);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private void insertSku(int id, int sales, Integer fakeSales) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_sku (id, shop_id, category_id, name, price, sales, fake_sales, type, status, sort) "
                             + "VALUES (?,1,1,?,?,?,?,1,1,?)")) {
            ps.setInt(1, id);
            ps.setString(2, "商品" + id);
            ps.setBigDecimal(3, new BigDecimal("9.90"));
            ps.setInt(4, sales);
            if (fakeSales == null) {
                ps.setNull(5, java.sql.Types.INTEGER);
            } else {
                ps.setInt(5, fakeSales);
            }
            ps.setInt(6, id);
            ps.executeUpdate();
        }
    }

    private MallSkuQuery query() {
        MallSkuQuery q = new MallSkuQuery();
        q.setPage(1);
        q.setSize(100);
        return q;
    }

    private Map<Integer, SkuPO> byId(List<SkuPO> rows) {
        return rows.stream().collect(Collectors.toMap(SkuPO::getId, Function.identity()));
    }

    // ============ 1. 字段级校验 + 2. 类型与格式：displaySales 精确合成 ============

    static Stream<Arguments> displaySalesCases() {
        return Stream.of(
                arguments("正常合成 sales+fake", 1, 15),
                arguments("fake为null按0计", 2, 12),
                arguments("sales与fake均为0/null", 3, 0)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("displaySalesCases")
    void displaySales计算列_精确合成(String caseName, int id, Integer expected) {
        SkuPO po = byId(skuMapper.selectMallPage(query())).get(id);

        assertNotNull(po, caseName + "：商品 id=" + id + " 应在上架结果中");
        assertEquals(expected, po.getDisplaySales(),
                caseName + "：displaySales 必须 = sales + COALESCE(fake_sales,0)（DAO 计算列），而非 null 或 VO 另算");
    }

    // ============ 2. 类型与格式：displaySales 为 Integer 非 null（fake null 不导致 null） ============

    @Test
    void displaySales_类型为Integer且fake为null不为null() {
        SkuPO id2 = byId(skuMapper.selectMallPage(query())).get(2);
        assertNotNull(id2.getDisplaySales(),
                "fake_sales=null 时 displaySales 必须为合成数值（按 0 计），不得为 null");
        assertEquals(Integer.valueOf(12), id2.getDisplaySales(),
                "fake_sales=null 时 displaySales = sales(12) + 0 = 12");
    }

    // ============ 3. 重复创建：同查询重复调用 displaySales 一致 ============

    @Test
    void 重复查询_displaySales一致() {
        SkuPO first = byId(skuMapper.selectMallPage(query())).get(1);
        SkuPO second = byId(skuMapper.selectMallPage(query())).get(1);

        assertEquals(first.getDisplaySales(), second.getDisplaySales(),
                "同查询重复调用 displaySales 必须一致（幂等）");
    }

    // ============ 4. 非法输入：sales=0 且 fake=null -> displaySales=0（非 null，不伪造） ============

    @Test
    void sales为0且fake为null_displaySales为0非null() {
        SkuPO id3 = byId(skuMapper.selectMallPage(query())).get(3);
        assertEquals(Integer.valueOf(0), id3.getDisplaySales(),
                "sales=0 且 fake_sales=null 时 displaySales 必须为 0（而非 null），不得伪造销量");
    }
}
