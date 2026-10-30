package com.ysh.b2cmall.product.dao.mapper;

import com.ysh.b2cmall.product.dao.po.CategoryPO;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * CategoryMapper 商品分类下拉 SQL 的真实 SQLite 测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-product-list.md 功能上下文 3.3（商品分类下拉数据）：
 * - 3.3.1 只返回当前登录店铺 status=1 的类目（id+name），按 sort 升序
 *   （Fail 边界：返回他店/禁用类目 -> 筛选出他店商品）
 *
 * 用例分类（表格驱动，适配 DAO 查询结果）：
 * 1. 字段级校验：返回本店启用类目的 id 与 name，且按 sort 升序
 * 2. 类型与格式：id 为整数、name 非空；空结果返回空集合（不伪造）
 * 3. 重复创建：重复查询结果完全一致（幂等、不串态）
 * 4. 非法输入：他店数据不可见；shopId 为 null 时隔离生效返回空集合
 *
 * 测试库为 @TempDir 下的临时 SQLite 库（绝对路径连接串），固定数据：
 * 店铺 1：男装(sort=1,启用)、女装(sort=2,启用)、童装(sort=3,禁用)
 * 店铺 2：数码(sort=1,启用)、图书(sort=2,启用)
 * 店铺 3：无类目
 */
class CategoryMapperTest {

    @TempDir
    File tempDir;

    private UnpooledDataSource dataSource;
    private SqlSession session;
    private CategoryMapper categoryMapper;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + new File(tempDir, "product-category-test.db").getAbsolutePath();
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

        insertCategory(1, "男装", 1, 1);
        insertCategory(1, "女装", 2, 1);
        insertCategory(1, "童装", 3, 0);
        insertCategory(2, "数码", 1, 1);
        insertCategory(2, "图书", 2, 1);

        session = sqlSessionFactory.openSession(true);
        categoryMapper = session.getMapper(CategoryMapper.class);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private void insertCategory(int shopId, String name, int sort, int status) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_category (shop_id, name, parent_id, sort, status) VALUES (?,?,?,?,?)")) {
            ps.setInt(1, shopId);
            ps.setString(2, name);
            ps.setInt(3, 0);
            ps.setInt(4, sort);
            ps.setInt(5, status);
            ps.executeUpdate();
        }
    }

    private List<String> namesOf(List<CategoryPO> categories) {
        return categories.stream().map(CategoryPO::getName).collect(Collectors.toList());
    }

    // ============ 3.3.1 本店启用类目：字段齐全 + sort 升序（表格驱动） ============

    /**
     * 列：用例名、查询的 shopId、期望类目名称（顺序即 sort 升序）
     */
    static Stream<Arguments> enabledCategoryCases() {
        return Stream.of(
                // ----1. 字段级校验：只返回本店启用类目，按 sort 升序----
                arguments("店1只返回2个启用类目_按sort升序", 1, Arrays.asList("男装", "女装")),

                // ----2. 类型与格式：空结果返回空集合，不伪造任何项----
                arguments("无类目店铺返回空集合", 3, Collections.emptyList()),

                // ----4. 非法输入：他店数据不可见；shopId 为 null 时隔离生效----
                arguments("店2只看到自己的类目", 2, Arrays.asList("数码", "图书")),
                arguments("shopId为null返回空集合", null, Collections.emptyList())
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("enabledCategoryCases")
    void selectEnabledByShopId(String caseName, Integer shopId, List<String> expectedNames) {
        List<CategoryPO> categories = categoryMapper.selectEnabledByShopId(shopId);

        assertEquals(expectedNames, namesOf(categories), caseName + "：类目集合或顺序不符");
        for (CategoryPO category : categories) {
            assertTrue(category.getId() != null && category.getId() > 0, caseName + "：id 必须为有效整数");
            assertTrue(category.getName() != null && !category.getName().isEmpty(), caseName + "：name 不得为空");
        }
    }

    @Test
    void selectEnabledByShopId_禁用类目不出现在下拉中() {
        List<String> names = namesOf(categoryMapper.selectEnabledByShopId(1));

        assertTrue(!names.contains("童装"), "status=0 的禁用类目不得出现在下拉中，实际：" + names);
    }

    // ============ 3. 重复创建：重复查询结果完全一致（幂等、不串态） ============

    @Test
    void selectEnabledByShopId_重复查询结果一致() {
        List<CategoryPO> first = categoryMapper.selectEnabledByShopId(1);
        List<CategoryPO> second = categoryMapper.selectEnabledByShopId(1);

        List<Integer> firstIds = new ArrayList<>();
        for (CategoryPO category : first) {
            firstIds.add(category.getId());
        }
        List<Integer> secondIds = new ArrayList<>();
        for (CategoryPO category : second) {
            secondIds.add(category.getId());
        }
        assertEquals(firstIds, secondIds, "重复查询的 id 顺序必须一致");
        assertEquals(namesOf(first), namesOf(second), "重复查询的名称必须一致");
        assertTrue(firstIds.stream().allMatch(Objects::nonNull), "id 不得为空");
    }
}
