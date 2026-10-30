package com.ysh.b2cmall.product.dao.mapper;

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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * CategoryMapper.selectMallTopLevelNames() 的真实 SQLite 测试（TDD 红阶段）。
 * 对应 .tdd/task/mall-category-product-list.md 功能上下文 1.1（C 端分类导航 DAO）：
 * - 1.1.1 跨店聚合 + 按名去重（GROUP BY name），不带 shop_id 条件
 * - 1.1.2 排序确定性：ORDER BY MAX(sort) ASC, name ASC
 * - 1.1.3 禁用分类（status=0）与子分类（parent_id≠0）不出现在结果
 *
 * 用例分类（表格驱动，适配 DAO 返回 List&lt;String&gt;）：
 * 1. 字段级校验：跨店同名一级分类只出现一次；返回纯 name 字符串
 * 2. 类型与格式：结果为 String 列表、元素非空；空结果返回空集合（不伪造）
 * 3. 重复创建：重复查询结果顺序完全一致（幂等、排序稳定）
 * 4. 非法输入：禁用分类、子分类不得出现（无入参接口，非法输入聚焦数据侧）
 *
 * 测试库为 @TempDir 下的临时 SQLite 库（绝对路径连接串），固定数据：
 * 店铺 1：水果(sort=1,parent=0,启用)、蔬菜(sort=5,parent=0,启用)、海鲜(sort=9,parent=0,禁用)、海鲜·虾(sort=1,parent=9,启用)
 * 店铺 2：水果(sort=3,parent=0,启用)、数码(sort=2,parent=0,启用)
 */
class CategoryMallMapperTest {

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private CategoryMapper categoryMapper;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "product-mall-category-test.db").getAbsolutePath();
        dataSource = new UnpooledDataSource("org.sqlite.JDBC", jdbcUrl, null, null);

        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        try (InputStream in = Resources.getResourceAsStream("mapper/CategoryMapper.xml")) {
            new XMLMapperBuilder(in, configuration, "mapper/CategoryMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);

        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
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

        // 店铺 1：水果/蔬菜启用一级，海鲜禁用一级，海鲜·虾为子分类
        insertCategory(1, "水果", 0, 1, 1);
        insertCategory(1, "蔬菜", 0, 5, 1);
        insertCategory(1, "海鲜", 0, 9, 0);
        insertCategory(1, "海鲜·虾", 9, 1, 1);
        // 店铺 2：同名「水果」sort 更大（跨店去重取 MAX(sort)），另有数码
        insertCategory(2, "水果", 0, 3, 1);
        insertCategory(2, "数码", 0, 2, 1);

        session = sqlSessionFactory.openSession(true);
        categoryMapper = session.getMapper(CategoryMapper.class);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private void insertCategory(int shopId, String name, int parentId, int sort, int status) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_category (shop_id, name, parent_id, sort, status) VALUES (?,?,?,?,?)")) {
            ps.setInt(1, shopId);
            ps.setString(2, name);
            ps.setInt(3, parentId);
            ps.setInt(4, sort);
            ps.setInt(5, status);
            ps.executeUpdate();
        }
    }

    // ============ 1. 字段级校验 + 2. 类型与格式：跨店去重、纯 name、非空（表格驱动） ============

    /**
     * 列：用例名、期望返回的分类名列表（顺序即 MAX(sort), name 排序规则）
     */
    static Stream<Arguments> mallCategoryCases() {
        return Stream.of(
                // ----1. 字段级校验：跨店同名「水果」只出现一次；禁用「海鲜」、子分类「海鲜·虾」均不出现----
                // 顺序遵循 MAX(sort) ASC, name ASC：数码(2) < 水果(3) < 蔬菜(5)
                arguments("跨店同名去重_禁用与子分类排除", Arrays.asList("数码", "水果", "蔬菜"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mallCategoryCases")
    void selectMallTopLevelNames_字段与去重(String caseName, List<String> expectedNames) {
        List<String> names = categoryMapper.selectMallTopLevelNames();

        assertEquals(expectedNames, names, caseName + "：分类名集合或顺序不符");
        for (String name : names) {
            assertTrue(name != null && !name.isEmpty(), caseName + "：name 不得为空");
        }
        // 跨店同名去重：结果中不得出现重复名称
        long distinctCount = names.stream().distinct().count();
        assertEquals(names.size(), distinctCount, caseName + "：存在重复分类名（漏 GROUP BY）");
    }

    // ============ 1.1.2 排序确定性：MAX(sort) ASC, name ASC（独立断言，避免与去重混淆） ============

    @Test
    void selectMallTopLevelNames_按MaxSort升序() {
        List<String> names = categoryMapper.selectMallTopLevelNames();

        // 水果跨店 MAX(sort)=3、数码 sort=2、蔬菜 sort=5 → 期望 数码(2) < 水果(3) < 蔬菜(5)
        assertEquals(Arrays.asList("数码", "水果", "蔬菜"), names,
                "应按 MAX(sort) ASC, name ASC 排序，实际：" + names);
    }

    // ============ 3. 重复创建：重复查询结果完全一致（排序稳定、幂等） ============

    @Test
    void selectMallTopLevelNames_重复查询结果一致() {
        List<String> first = categoryMapper.selectMallTopLevelNames();
        List<String> second = categoryMapper.selectMallTopLevelNames();

        assertEquals(first, second, "重复查询的分类名与顺序必须一致（排序确定性）");
    }

    // ============ 4. 非法输入：禁用分类、子分类不得出现 ============

    @Test
    void selectMallTopLevelNames_禁用分类不出现在导航中() {
        List<String> names = categoryMapper.selectMallTopLevelNames();

        assertFalse(names.contains("海鲜"), "status=0 的禁用一级分类不得出现，实际：" + names);
    }

    @Test
    void selectMallTopLevelNames_子分类不出现在导航中() {
        List<String> names = categoryMapper.selectMallTopLevelNames();

        assertFalse(names.contains("海鲜·虾"), "parent_id≠0 的子分类不得混入一级导航，实际：" + names);
    }
}
