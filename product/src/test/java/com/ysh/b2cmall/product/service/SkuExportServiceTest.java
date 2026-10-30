package com.ysh.b2cmall.product.service;

import com.ysh.b2cmall.product.dao.mapper.SkuMapper;
import com.ysh.b2cmall.product.dao.po.SkuPO;
import com.ysh.b2cmall.product.dao.po.SkuQuery;
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
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 商品列表导出数据组装的表格驱动测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-product-list.md 功能上下文 5.1（Service 层 - 导出数据组装）：
 * - 5.1.1 导出与列表共用同一筛选构建器：按当前筛选条件取全量（不分页）数据行
 *   （Fail 边界：忽略筛选导出全店数据）
 *
 * 用例分类（表格驱动，适配导出查询行为）：
 * 1. 字段级校验：命中行包含列表所需字段，且逐行属于筛选结果
 * 2. 类型与格式：全量返回（不受分页 size 限制），顺序与列表一致（sort DESC, id DESC）
 * 3. 重复创建：同条件重复导出结果完全一致
 * 4. 非法输入：他店数据不可见（shopId 隔离）；shopId 为 null 返回空集合
 *
 * 测试库为 @TempDir 下的临时 SQLite 库，真实 SkuMapper XML + 真实 SkuServiceImpl。
 * 固定数据：店铺 1 共 12 条（商品1..商品12，sort=id）：
 *   商品1/2/3 名称含「咖啡」；奇数 id 上架(1) 偶数 id 下架(0)；
 *   商品1-6 productType=1（普通），商品7-12 productType=2（秒杀）；
 *   商品1-4 配送 EXPRESS，商品5-8 CITY，商品9-12 PICKUP,EXPRESS
 * 店铺 2 共 2 条（他店绊线）。12 条超过默认 size=10，用于证明导出不分页。
 */
class SkuExportServiceTest {

    @TempDir
    File tempDir;

    private SqlSession session;
    private SkuMapper skuMapper;
    private SkuServiceImpl skuService;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "product-export-test.db").getAbsolutePath();
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

        for (int i = 1; i <= 12; i++) {
            String name = i <= 3 ? "咖啡" + (char) ('A' + i - 1) : "商品" + i;
            int status = i % 2;
            int productType = i <= 6 ? 1 : 2;
            String delivery = i <= 4 ? "EXPRESS" : (i <= 8 ? "CITY" : "PICKUP,EXPRESS");
            insertSku(dataSource, 1, i, name, status, productType, delivery);
        }
        insertSku(dataSource, 2, 101, "他店商品甲", 1, 1, "EXPRESS");
        insertSku(dataSource, 2, 102, "他店商品乙", 1, 2, "CITY");

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

    private void insertSku(UnpooledDataSource dataSource, int shopId, int sort, String name,
                           int status, int productType, String delivery) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_sku (shop_id, category_id, name, price, market_price, stock, store_stock,"
                             + " sales, fake_sales, type, product_type, images, delivery_methods, sort, status)"
                             + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setInt(1, shopId);
            ps.setInt(2, 100);
            ps.setString(3, name);
            ps.setBigDecimal(4, new BigDecimal("10.00"));
            ps.setBigDecimal(5, new BigDecimal("20.00"));
            ps.setInt(6, 7);
            ps.setInt(7, 3);
            ps.setInt(8, 11);
            ps.setInt(9, 5);
            ps.setInt(10, 1);
            ps.setInt(11, productType);
            ps.setString(12, "a.jpg,b.jpg");
            ps.setString(13, delivery);
            ps.setInt(14, sort);
            ps.setInt(15, status);
            ps.executeUpdate();
        }
    }

    private SkuQuery shopQuery(Integer shopId) {
        SkuQuery query = new SkuQuery();
        query.setShopId(shopId);
        return query;
    }

    private List<String> namesOf(List<SkuPO> rows) {
        return rows.stream().map(SkuPO::getName).collect(Collectors.toList());
    }

    // ============ 5.1.1 与列表共用同一筛选，取全量不分页（表格驱动） ============

    /**
     * 列：用例名、筛选配置、期望商品名称（顺序即 sort DESC, id DESC）
     */
    static Stream<Arguments> exportCases() {
        return Stream.of(
                // ----1. 字段级校验 + 2. 类型与格式：无筛选导出本店全部 12 条（超过默认 size=10，证明不分页）----
                arguments("无筛选导出本店全部12条_不受分页限制",
                        (Consumer<SkuQuery>) q -> { },
                        Arrays.asList("商品12", "商品11", "商品10", "商品9", "商品8", "商品7",
                                "商品6", "商品5", "商品4", "咖啡C", "咖啡B", "咖啡A")),

                // ----1. 字段级校验：与列表一致的各维度筛选----
                arguments("名称模糊只导出命中行",
                        (Consumer<SkuQuery>) q -> q.setName("咖啡"),
                        Arrays.asList("咖啡C", "咖啡B", "咖啡A")),
                arguments("状态筛选只导出上架",
                        (Consumer<SkuQuery>) q -> q.setStatus(1),
                        Arrays.asList("商品11", "商品9", "商品7", "商品5", "咖啡C", "咖啡A")),
                arguments("商品类型筛选只导出秒杀",
                        (Consumer<SkuQuery>) q -> q.setProductType(2),
                        Arrays.asList("商品12", "商品11", "商品10", "商品9", "商品8", "商品7")),
                arguments("配送方式多选命中任一即导出",
                        (Consumer<SkuQuery>) q -> q.setDeliveryMethods(Arrays.asList("CITY", "PICKUP")),
                        Arrays.asList("商品12", "商品11", "商品10", "商品9", "商品8", "商品7", "商品6", "商品5")),
                arguments("组合筛选_名称加状态",
                        (Consumer<SkuQuery>) q -> {
                            q.setName("商品");
                            q.setStatus(0);
                        },
                        Arrays.asList("商品12", "商品10", "商品8", "商品6", "商品4")),

                // ----4. 非法输入：他店数据不可见；shopId 为 null 返回空集合----
                arguments("他店导出只含自己商品",
                        (Consumer<SkuQuery>) q -> q.setShopId(2),
                        Arrays.asList("他店商品乙", "他店商品甲")),
                arguments("shopId为null返回空集合",
                        (Consumer<SkuQuery>) q -> q.setShopId(null),
                        Collections.emptyList())
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("exportCases")
    void 导出_共用列表筛选_全量不分页(String caseName, Consumer<SkuQuery> configurer, List<String> expectedNames) {
        SkuQuery query = shopQuery(1);
        configurer.accept(query);

        List<SkuPO> rows = skuService.exportList(query);

        assertNotNull(rows, caseName + "：导出结果不得为 null");
        assertEquals(expectedNames, namesOf(rows), caseName + "：命中集合或顺序不符");
    }

    @Test
    void 导出行_包含列表所需字段() {
        List<SkuPO> rows = skuService.exportList(shopQuery(1));

        SkuPO first = rows.get(0);
        assertNotNull(first.getId(), "skuId 不得为空");
        assertNotNull(first.getName(), "名称不得为空");
        assertNotNull(first.getMarketPrice(), "市场价不得为空");
        assertNotNull(first.getPrice(), "销售价不得为空");
        assertNotNull(first.getSales(), "商品销量不得为空");
        assertNotNull(first.getFakeSales(), "注水销量不得为空");
        assertNotNull(first.getStock(), "仓库库存不得为空");
        assertNotNull(first.getStoreStock(), "门店库存不得为空");
        assertNotNull(first.getProductType(), "商品类型不得为空");
        assertNotNull(first.getType(), "商品类别不得为空");
        assertNotNull(first.getStatus(), "状态不得为空");
        assertNotNull(first.getDeliveryMethods(), "配送方式不得为空");
        assertNotNull(first.getSort(), "排序不得为空");
        assertNotNull(first.getImages(), "images 不得为空（导出缩略图来源）");
    }

    // ============ 3. 重复创建：同条件重复导出结果完全一致 ============

    @Test
    void 同条件重复导出_结果完全一致() {
        SkuQuery query = shopQuery(1);
        query.setStatus(1);

        List<SkuPO> first = skuService.exportList(query);
        List<SkuPO> second = skuService.exportList(query);

        assertEquals(first.stream().map(SkuPO::getId).collect(Collectors.toList()),
                second.stream().map(SkuPO::getId).collect(Collectors.toList()),
                "重复导出的 id 顺序必须一致");
        assertEquals(namesOf(first), namesOf(second), "重复导出的名称必须一致");
    }
}
