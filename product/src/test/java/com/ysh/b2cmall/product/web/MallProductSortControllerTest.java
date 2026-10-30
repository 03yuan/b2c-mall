package com.ysh.b2cmall.product.web;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ysh.b2cmall.product.dao.po.MallSkuQuery;
import com.ysh.b2cmall.product.dao.po.MallSortType;
import com.ysh.b2cmall.product.dao.po.SkuPageResult;
import com.ysh.b2cmall.product.service.CategoryService;
import com.ysh.b2cmall.product.service.SkuService;
import com.ysh.b2cmall.product.web.advice.GlobalExceptionHandler;
import com.ysh.b2cmall.product.web.controller.MallProductController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * C 端商品卡片列表 GET /product/mall/products 的 sortBy 参数校验测试（TDD 红阶段）。
 * 对应 .tdd/task/mall-category-product-list.md 功能上下文 3.2（Controller - sortBy 参数校验）：
 * - 3.2.1 sortBy 缺省 -> COMPREHENSIVE（不传 sortBy 时 DAO 收到综合排序）
 * - 3.2.2 合法值 COMPREHENSIVE / SALES / PRICE_ASC / PRICE_DESC -> 200；
 *   其他值（''、'price'、'SORT' 混写、小写、注入尝试等）-> 400（严禁静默降级为综合）
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：缺省 sortBy -> Service 收到 COMPREHENSIVE
 * 2. 类型与格式：4 个合法值 -> Service 收到对应枚举
 * 3. 重复创建：同 sortBy 重复请求响应一致
 * 4. 非法输入：非法 sortBy -> 400 且含中文原因；不得静默当综合
 *
 * standalone MockMvc + mock SkuService/CategoryService + GlobalExceptionHandler，无拦截器。
 */
class MallProductSortControllerTest {

    private CategoryService categoryService;
    private SkuService skuService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        categoryService = mock(CategoryService.class);
        skuService = mock(SkuService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new MallProductController(categoryService, skuService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ============ 1. 字段级校验：缺省 sortBy -> COMPREHENSIVE（3.2.1） ============

    @Test
    void sortBy缺省_Service收到COMPREHENSIVE() throws Exception {
        when(skuService.queryMallPage(any())).thenReturn(new SkuPageResult(Collections.emptyList(), 0));

        mockMvc.perform(get("/product/mall/products")).andReturn();

        ArgumentCaptor<MallSkuQuery> captor = ArgumentCaptor.forClass(MallSkuQuery.class);
        verify(skuService, only()).queryMallPage(captor.capture());
        assertEquals(MallSortType.COMPREHENSIVE, captor.getValue().getSortBy(),
                "sortBy 缺省必须为 COMPREHENSIVE（综合排序），而非 null 或其它");
    }

    // ============ 2. 类型与格式：4 个合法值 -> 对应枚举（3.2.2） ============

    static Stream<Arguments> legalSortCases() {
        return Stream.of(
                arguments("COMPREHENSIVE", MallSortType.COMPREHENSIVE),
                arguments("SALES", MallSortType.SALES),
                arguments("PRICE_ASC", MallSortType.PRICE_ASC),
                arguments("PRICE_DESC", MallSortType.PRICE_DESC)
        );
    }

    @ParameterizedTest(name = "合法 sortBy={0}")
    @MethodSource("legalSortCases")
    void 合法sortBy_Service收到对应枚举(String sortBy, MallSortType expected) throws Exception {
        when(skuService.queryMallPage(any())).thenReturn(new SkuPageResult(Collections.emptyList(), 0));

        MockHttpServletResponse response = mockMvc.perform(get("/product/mall/products")
                .param("sortBy", sortBy)).andReturn().getResponse();

        assertEquals(200, response.getStatus(), "合法 sortBy=" + sortBy + " 必须返回 200");

        ArgumentCaptor<MallSkuQuery> captor = ArgumentCaptor.forClass(MallSkuQuery.class);
        verify(skuService, only()).queryMallPage(captor.capture());
        assertEquals(expected, captor.getValue().getSortBy(),
                "sortBy=" + sortBy + " 必须映射为对应枚举 " + expected);
    }

    // ============ 4. 非法输入：非法 sortBy -> 400（3.2.2） ============

    static Stream<Arguments> illegalSortCases() {
        return Stream.of(
                arguments("空串", ""),
                arguments("小写price", "price"),
                arguments("无意义SORT", "SORT"),
                arguments("小写price_asc", "price_asc"),
                arguments("中文", "综合"),
                arguments("注入尝试", "SALES DESC"),
                arguments("数字", "123")
        );
    }

    @ParameterizedTest(name = "非法 sortBy={0}")
    @MethodSource("illegalSortCases")
    void 非法sortBy_返回400且含中文原因(String caseName, String sortBy) throws Exception {
        when(skuService.queryMallPage(any())).thenReturn(new SkuPageResult(Collections.emptyList(), 0));

        MockHttpServletResponse response = mockMvc.perform(get("/product/mall/products")
                .param("sortBy", sortBy)).andReturn().getResponse();

        assertEquals(400, response.getStatus(),
                caseName + "：非法 sortBy 必须拒绝为 400，实际：" + response.getStatus());
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("排序"), caseName + "：400 响应应含中文原因「排序」，实际：" + body);
    }

    // ============ 3. 重复创建：同 sortBy 重复请求响应一致 ============

    @Test
    void 同sortBy重复请求_响应一致() throws Exception {
        when(skuService.queryMallPage(any())).thenReturn(new SkuPageResult(Collections.emptyList(), 0));

        String first = mockMvc.perform(get("/product/mall/products").param("sortBy", "SALES"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String second = mockMvc.perform(get("/product/mall/products").param("sortBy", "SALES"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JSONObject firstData = JSONUtil.parseObj(first).getJSONObject("data");
        JSONObject secondData = JSONUtil.parseObj(second).getJSONObject("data");
        assertEquals(firstData.toString(), secondData.toString(), "同 sortBy 重复请求必须返回一致结果");
    }
}
