package com.ysh.b2cmall.product.web;

import com.ysh.b2cmall.product.web.filter.TokenFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Method;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * TokenFilter.shouldNotFilter 白名单放行的契约测试（TDD 红阶段）。
 * 对应 .tdd/task/mall-category-product-list.md 功能上下文 2.1.2（product-service 白名单）：
 * - 2.1.2 TokenFilter.shouldNotFilter 新增 /product/mall 前缀放行（公开读接口免登录）
 *   （Fail 边界：只配网关白名单漏服务端 -> 直连/限流旁路时 401）
 *
 * 用例分类（表格驱动，适配 shouldNotFilter 的布尔契约）：
 * 1. 字段级校验：/product/mall/** 路径应被放行（返回 true）
 * 2. 类型与格式：放行判定为前缀匹配（startsWith "/product/mall"），子路径均命中
 * 3. 重复创建：同一路径重复判定结果一致（幂等）
 * 4. 非法输入：受保护路径（非白名单）应返回 false，不被误放行
 *
 * shouldNotFilter 为 OncePerRequestFilter 的 protected 方法，通过反射调用；
 * 该方法不访问任何实例字段，故直接 new TokenFilter() 即可。
 */
class TokenFilterWhiteListTest {

    private TokenFilter tokenFilter;

    @BeforeEach
    void setUp() {
        tokenFilter = new TokenFilter();
    }

    /** 反射调用 protected shouldNotFilter(HttpServletRequest) */
    private boolean shouldNotFilter(String requestUri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(requestUri);
        Method method = TokenFilter.class.getDeclaredMethod("shouldNotFilter", javax.servlet.http.HttpServletRequest.class);
        method.setAccessible(true);
        return (boolean) method.invoke(tokenFilter, request);
    }

    // ============ 1. 字段级校验 + 2. 类型与格式：/product/mall 前缀放行（表格驱动） ============

    /**
     * 列：用例名、请求 URI、期望是否放行（true=白名单免登录）
     */
    static Stream<Arguments> mallPathCases() {
        return Stream.of(
                // ----1/2. C 端 mall 公开路径（含子路径）必须放行----
                arguments("分类导航放行", "/product/mall/categories", true),
                arguments("商品列表放行", "/product/mall/products", true),
                arguments("mall其他子路径放行", "/product/mall/foo/bar", true)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mallPathCases")
    void shouldNotFilter_C端mall路径放行(String caseName, String uri, boolean expected) throws Exception {
        assertEquals(expected, shouldNotFilter(uri),
                caseName + "：/product/mall/** 应为白名单（免登录可访问）");
    }

    // ============ 4. 非法输入：受保护路径不得被误放行（表格驱动） ============

    static Stream<Arguments> protectedPathCases() {
        return Stream.of(
                arguments("商品列表需登录", "/product/list", false),
                arguments("商品详情需登录", "/product/detail/10", false),
                arguments("商品创建需登录", "/product/create", true), // 既有白名单，须保持
                arguments("商品导出需登录", "/product/export", false)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedPathCases")
    void shouldNotFilter_受保护路径不放行或保持既有白名单(String caseName, String uri, boolean expected) throws Exception {
        assertEquals(expected, shouldNotFilter(uri), caseName + "：白名单判定不符");
    }

    // ============ 3. 重复创建：同一路径重复判定结果一致 ============

    @Test
    void shouldNotFilter_重复判定结果一致() throws Exception {
        boolean first = shouldNotFilter("/product/mall/categories");
        boolean second = shouldNotFilter("/product/mall/categories");

        assertEquals(first, second, "同一路径重复判定结果必须一致");
    }
}
