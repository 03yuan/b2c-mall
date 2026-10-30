package com.ysh.b2cmall.product.service;

import com.alibaba.excel.EasyExcel;
import com.ysh.b2cmall.product.dao.mapper.SkuMapper;
import com.ysh.b2cmall.product.dao.po.SkuPO;
import com.ysh.b2cmall.product.dao.po.SkuQuery;
import com.ysh.b2cmall.product.service.impl.SkuServiceImpl;
import com.ysh.b2cmall.product.web.excel.ProductExcelColumns;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 商品导入 Excel 的 Service 层表格驱动测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-product-list.md 功能上下文 6.1（解析、校验、按 ID 分流）：
 * - 6.1.1 逐行字段校验：必填（名称、销售价、商品类型、商品类别、状态）、价格非负数字、
 *   库存/销量/排序非负整数、类型/类别/状态/配送方式枚举内，错误行收集「行号+原因」
 *   （Fail 边界：一行错 -> 整个文件 500 无定位信息）
 * - 6.1.2 按商品ID 分流：ID 空 -> 新增；ID 存在且属本店 -> 更新；ID 不存在或属他店 -> 该行报错；
 *   逐行独立，好行照常入库
 *   （Fail 边界：他店 ID 被更新 -> 越权写入；全文件被错误行连坐回滚）
 * - 6.1.3 销量字段保护：sales 以 DB 为准不被 Excel 覆盖（可改注水销量，不改真实销量）
 *   （Fail 边界：真实销量被随意改写 -> 数据失真）
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：合法行字段映射正确入库
 * 2. 类型与格式：各非法字段值 -> 错误行含行号+中文原因，DB 零改动
 * 3. 重复创建：同一文件重复导入，第二次转为更新（幂等语义）
 * 4. 非法输入：他店/不存在 ID 该行报错，好行照常入库
 *
 * 测试库为 @TempDir 临时 SQLite 库，真实 SkuMapper XML + 真实 SkuServiceImpl；
 * Excel 文件用 EasyExcel 按 ProductExcelColumns.HEADERS（单一列定义）内存生成。
 * 固定数据：本店商品 id=10（sales=100, fakeSales=5）；他店商品 id=20（shopId=2）。
 */
class SkuImportServiceTest {

    @TempDir
    File tempDir;

    private SqlSession session;
    private SkuMapper skuMapper;
    private SkuServiceImpl skuService;

    private static final int OWN_SKU_ID = 10;
    private static final int OTHER_SHOP_SKU_ID = 20;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "product-import-test.db").getAbsolutePath();
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

        insertSku(dataSource, OWN_SKU_ID, 1, "旧名称", 100, 5);
        insertSku(dataSource, OTHER_SHOP_SKU_ID, 2, "他店商品", 50, 0);

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

    private void insertSku(UnpooledDataSource dataSource, int id, int shopId, String name,
                           int sales, int fakeSales) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_sku (id, shop_id, category_id, name, price, stock, store_stock,"
                             + " sales, fake_sales, type, product_type, delivery_methods, sort, status)"
                             + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setInt(1, id);
            ps.setInt(2, shopId);
            ps.setInt(3, 100);
            ps.setString(4, name);
            ps.setBigDecimal(5, new BigDecimal("10.00"));
            ps.setInt(6, 7);
            ps.setInt(7, 3);
            ps.setInt(8, sales);
            ps.setInt(9, fakeSales);
            ps.setInt(10, 1);
            ps.setInt(11, 1);
            ps.setString(12, "EXPRESS");
            ps.setInt(13, 1);
            ps.setInt(14, 1);
            ps.executeUpdate();
        }
    }

    /** 合法行模板（13 列，与 HEADERS 一一对应）：ID,名称,市场价,销售价,销量,注水销量,仓库库存,门店库存,商品类型,商品类别,状态,配送方式,排序 */
    private List<String> validRow(String id) {
        return Arrays.asList(id, "新商品", "20.00", "10.00", "0", "0", "7", "3", "1", "1", "1", "EXPRESS", "5");
    }

    private byte[] xlsx(List<List<String>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<List<String>> head = ProductExcelColumns.HEADERS.stream()
                .map(Collections::singletonList)
                .collect(Collectors.toList());
        EasyExcel.write(out).head(head).sheet(0).doWrite(rows);
        return out.toByteArray();
    }

    private ImportResult doImport(List<List<String>> rows) {
        return skuService.importExcel(1, new ByteArrayInputStream(xlsx(rows)));
    }

    private int countOwnShopSkus() {
        SkuQuery query = new SkuQuery();
        query.setShopId(1);
        return skuMapper.countByFilter(query);
    }

    private boolean hasError(ImportResult result, String rowNo, String reasonFragment) {
        return result.getErrors().stream()
                .anyMatch(e -> e.contains("第" + rowNo + "行") && e.contains(reasonFragment));
    }

    // ============ 6.1.1 字段级校验：合法行入库且字段映射正确 ============

    @Test
    void 合法行_新增入库且字段映射正确() {
        ImportResult result = doImport(Collections.singletonList(validRow("")));

        assertEquals(1, result.getInserted(), "合法行应新增 1 条");
        assertEquals(0, result.getUpdated(), "不应有更新");
        assertEquals(0, result.getErrors().size(), "不应有错误行");

        SkuQuery query = new SkuQuery();
        query.setShopId(1);
        query.setName("新商品");
        List<SkuPO> rows = skuMapper.selectAllByFilter(query);
        assertEquals(1, rows.size(), "应新增 1 条本店商品");
        SkuPO po = rows.get(0);
        assertEquals("新商品", po.getName(), "名称应入库");
        assertEquals(0, new BigDecimal("20.00").compareTo(po.getMarketPrice()), "市场价应入库（数值相等，SQLite 不保留标度）");
        assertEquals(0, new BigDecimal("10.00").compareTo(po.getPrice()), "销售价应入库（数值相等，SQLite 不保留标度）");
        assertEquals(7, po.getStock().intValue(), "仓库库存应入库");
        assertEquals(3, po.getStoreStock().intValue(), "门店库存应入库");
        assertEquals(1, po.getProductType().intValue(), "商品类型应入库");
        assertEquals(1, po.getType().intValue(), "商品类别应入库");
        assertEquals(1, po.getStatus().intValue(), "状态应入库");
        assertEquals("EXPRESS", po.getDeliveryMethods(), "配送方式应入库");
        assertEquals(5, po.getSort().intValue(), "排序应入库");
        assertEquals(1, po.getShopId().intValue(), "shopId 必须为登录店铺");
    }

    // ============ 6.1.3 销量保护：新增/更新均不被 Excel 覆盖 ============

    @Test
    void 新增行_销量字段被保护_不采用Excel值() {
        List<String> row = validRow("");
        row.set(4, "999");

        ImportResult result = doImport(Collections.singletonList(row));

        assertEquals(1, result.getInserted(), "应新增成功");
        SkuQuery query = new SkuQuery();
        query.setShopId(1);
        query.setName("新商品");
        SkuPO po = skuMapper.selectAllByFilter(query).get(0);
        assertEquals(0, po.getSales().intValue(), "真实销量必须为默认 0，不得采用 Excel 值 999");
    }

    @Test
    void 更新行_sales保持原值_fakeSales可更新() {
        List<String> row = validRow(String.valueOf(OWN_SKU_ID));
        row.set(4, "999");
        row.set(5, "66");

        ImportResult result = doImport(Collections.singletonList(row));

        assertEquals(1, result.getUpdated(), "应更新成功");
        SkuPO po = skuMapper.selectById(OWN_SKU_ID);
        assertEquals(100, po.getSales().intValue(), "真实销量必须保持 DB 原值 100，不被 Excel 覆盖");
        assertEquals(66, po.getFakeSales().intValue(), "注水销量应允许更新为 66");
        assertEquals("新商品", po.getName(), "名称应被更新");
    }

    // ============ 6.1.1 类型与格式：非法字段值 -> 行号+原因，DB 零改动（表格驱动） ============

    /**
     * 列：用例名、被改列索引、非法值、期望原因关键词
     */
    static Stream<Arguments> illegalRowCases() {
        return Stream.of(
                arguments("缺名称", 1, "", "名称"),
                arguments("缺销售价", 3, "", "销售价"),
                arguments("销售价为负", 3, "-1", "销售价"),
                arguments("销售价非数字", 3, "abc", "销售价"),
                arguments("市场价为负", 2, "-1", "市场价"),
                arguments("仓库库存为负", 6, "-1", "库存"),
                arguments("仓库库存非整数", 6, "abc", "库存"),
                arguments("门店库存为负", 7, "-1", "库存"),
                arguments("排序非整数", 12, "abc", "排序"),
                arguments("商品类型非法", 8, "9", "商品类型"),
                arguments("商品类型为空", 8, "", "商品类型"),
                arguments("商品类别非法", 9, "5", "商品类别"),
                arguments("状态非法", 10, "2", "状态"),
                arguments("配送方式非法", 11, "FOO", "配送方式")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("illegalRowCases")
    void 非法字段_错误行含行号与原因_数据库零改动(String caseName, int col, String badValue, String reasonFragment) {
        List<String> row = validRow("");
        row.set(col, badValue);
        int before = countOwnShopSkus();

        ImportResult result = doImport(Collections.singletonList(row));

        assertEquals(0, result.getInserted(), caseName + "：非法行不得入库");
        assertEquals(0, result.getUpdated(), caseName + "：非法行不得更新");
        assertTrue(hasError(result, "2", reasonFragment),
                caseName + "：错误行应含「第2行」与「" + reasonFragment + "」，实际：" + result.getErrors());
        assertEquals(before, countOwnShopSkus(), caseName + "：非法行不得改变数据库");
    }

    // ============ 6.1.2 按 ID 分流：他店/不存在 ID 报错，好行照常入库 ============

    @Test
    void 混合三行_新增1更新1他店报错_他店零改动() {
        SkuPO otherBefore = skuMapper.selectById(OTHER_SHOP_SKU_ID);

        List<List<String>> rows = Arrays.asList(
                validRow(""),                                  // 行2：新增
                validRow(String.valueOf(OWN_SKU_ID)),          // 行3：更新本店
                validRow(String.valueOf(OTHER_SHOP_SKU_ID)));  // 行4：他店 ID -> 报错

        ImportResult result = doImport(rows);

        assertEquals(1, result.getInserted(), "ID 空应新增 1 条");
        assertEquals(1, result.getUpdated(), "本店 ID 应更新 1 条");
        assertTrue(hasError(result, "4", "不存在"), "他店 ID 应报「不存在」（不泄漏他店语义）");
        assertEquals(otherBefore, skuMapper.selectById(OTHER_SHOP_SKU_ID), "他店商品必须零改动");
    }

    @Test
    void 不存在的ID_该行报错_好行照常入库() {
        List<List<String>> rows = Arrays.asList(
                validRow(""),          // 行2：新增
                validRow("9999"));     // 行3：不存在 ID -> 报错

        ImportResult result = doImport(rows);

        assertEquals(1, result.getInserted(), "好行必须照常入库（不整批回滚）");
        assertTrue(hasError(result, "3", "不存在"), "不存在 ID 应报错");
    }

    // ============ 3. 重复创建：同一文件重复导入，第二次转为更新（幂等语义） ============

    @Test
    void 同一文件重复导入_第二次转为更新() {
        byte[] file = xlsx(Collections.singletonList(validRow("")));

        ImportResult first = skuService.importExcel(1, new ByteArrayInputStream(file));
        assertEquals(1, first.getInserted(), "首次导入应新增");

        SkuQuery query = new SkuQuery();
        query.setShopId(1);
        query.setName("新商品");
        Integer newId = skuMapper.selectAllByFilter(query).get(0).getId();
        assertNotNull(newId, "首次导入应产生新 ID");

        // 第二次带 ID 导入（等价于商家导出-修改-再导入）
        List<String> row2 = validRow(String.valueOf(newId));
        row2.set(1, "改名商品");
        ImportResult second = doImport(Collections.singletonList(row2));
        assertEquals(1, second.getUpdated(), "同 ID 再导入应转为更新");
        assertEquals(0, second.getInserted(), "不应重复新增");
        assertEquals("改名商品", skuMapper.selectById(newId).getName(), "名称应被更新");
        assertEquals(2, countOwnShopSkus(), "重复导入不得产生重复记录");
    }

    @Test
    void 空文件_零新增零更新零错误() {
        ImportResult result = doImport(new ArrayList<>());

        assertEquals(0, result.getInserted(), "空文件不得新增");
        assertEquals(0, result.getUpdated(), "空文件不得更新");
        assertEquals(0, result.getErrors().size(), "空文件不应有错误行");
    }
}
