package com.ysh.b2cmall.product.web;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ysh.b2cmall.product.dao.po.SkuPO;
import com.ysh.b2cmall.product.dao.po.SkuPageResult;
import com.ysh.b2cmall.product.service.CategoryService;
import com.ysh.b2cmall.product.service.SkuService;
import com.ysh.b2cmall.product.web.advice.GlobalExceptionHandler;
import com.ysh.b2cmall.product.web.controller.MallProductController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * C 端商品卡片字段语义测试（TDD 红阶段）。
 * 对应 .tdd/task/mall-category-product-list.md 功能上下文 5.2（Controller/VO - 字段语义钉死）：
 * - 5.2.1 thumbnail = images 首图（逗号分隔取第一张）；无图返回 null
 * - 5.2.2 name 返回完整名称，后端不做截断（超长截断为前端 CSS 职责）
 * - 5.2.3 price 以数值（BigDecimal，单位元）原样返回；红色字体与 ¥ 符号为前端样式职责
 * - 5.2.4 displaySales 只用合成值（DAO 计算列 displaySales）；VO 不暴露真实/注水分项
 * - 5.2.5 返回 skuId 供前端构造卡片跳转链接
 *
 * 用例分类（表格驱动语义）：
 * 1. 字段级校验：卡片五要素取值正确（thumbnail 首图、name 全名、price 数值、skuId=id、displaySales 来自 DAO 计算列）
 * 2. 类型与格式：price 为 BigDecimal 数值非格式化字符串；displaySales 为 Integer 非 null；name 80 字不截断
 * 3. 重复创建：同请求重复响应一致
 * 4. 非法输入：无图 thumbnail=null；displaySales 用 DAO 计算列而非 VO 重新合成（口径漂移拦截）
 *
 * 说明：5.2.1/5.2.2/5.2.3/5.2.5 已在 2.3 实现，本测试锁定语义；
 * 5.2.4 是红点：Controller 当前仍在 VO 层 sales+fake 合成，须改用 5.1 的 DAO 计算列 displaySales。
 *
 * standalone MockMvc + mock SkuService/CategoryService + GlobalExceptionHandler，无拦截器。
 */
class MallProductFieldTest {

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

    /** 全字段样例：displaySales 已由 DAO 计算列给出（15），sales/fake 仅为 PO 原始字段 */
    private SkuPO fullSkuPo() {
        SkuPO po = new SkuPO();
        po.setId(11);
        po.setName("测试商品");
        po.setPrice(new BigDecimal("88.00"));
        po.setSales(10);
        po.setFakeSales(5);
        po.setDisplaySales(15);  // DAO 计算列 (sales + COALESCE(fake_sales,0))
        po.setImages("https://img.test/a.jpg,https://img.test/b.jpg");
        return po;
    }

    private JSONObject fetchFirstItem() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/product/mall/products"))
                .andReturn().getResponse();
        JSONObject data = JSONUtil.parseObj(response.getContentAsString(StandardCharsets.UTF_8))
                .getJSONObject("data");
        JSONArray list = data.getJSONArray("list");
        return list.getJSONObject(0);
    }

    // ============ 5.2.1 thumbnail = images 首图 / 无图 null ============

    @Test
    void thumbnail_取images首图() throws Exception {
        when(skuService.queryMallPage(any())).thenReturn(new SkuPageResult(Collections.singletonList(fullSkuPo()), 1));

        JSONObject item = fetchFirstItem();
        assertEquals("https://img.test/a.jpg", item.getStr("thumbnail"),
                "缩略图必须为 images 逗号分隔的第一张图");
    }

    @Test
    void thumbnail_无图返回null不伪造() throws Exception {
        SkuPO po = fullSkuPo();
        po.setImages(null);
        when(skuService.queryMallPage(any())).thenReturn(new SkuPageResult(Collections.singletonList(po), 1));

        assertNull(fetchFirstItem().getStr("thumbnail"), "无图商品 thumbnail 必须为 null，不得伪造占位图");
    }

    // ============ 5.2.2 name 返回完整名称，不截断 ============

    @Test
    void name_返回完整名称不截断() throws Exception {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            longName.append("名");
        }
        SkuPO po = fullSkuPo();
        po.setName(longName.toString());
        when(skuService.queryMallPage(any())).thenReturn(new SkuPageResult(Collections.singletonList(po), 1));

        String name = fetchFirstItem().getStr("name");
        assertEquals(80, name.length(), "后端不得截断名称（超长截断为前端 CSS 职责），必须返回完整 " + 80 + " 字");
        assertEquals(longName.toString(), name, "名称必须原样返回，不得裁剪");
    }

    // ============ 5.2.3 price 数值原样返回（非格式化字符串） ============

    @Test
    void price_数值原样返回_非格式化字符串() throws Exception {
        when(skuService.queryMallPage(any())).thenReturn(new SkuPageResult(Collections.singletonList(fullSkuPo()), 1));

        JSONObject item = fetchFirstItem();
        assertEquals(0, item.getBigDecimal("price").compareTo(new BigDecimal("88.00")),
                "price 必须以数值（BigDecimal，单位元）原样返回，红色字体与 ¥ 符号为前端样式职责");
        // 类型锁定：price 必须是 JSON 数值，不得是 "¥88.00" 之类的字符串
        Object raw = item.get("price");
        assertEquals(BigDecimal.class, raw.getClass(), "price 必须是数值类型，而非带货币符/样式的字符串");
    }

    // ============ 5.2.4 displaySales 用 DAO 计算列（红点：不得 VO 重新 sales+fake 合成） ============

    @Test
    void displaySales_来自DAO计算列_不重新VO合成() throws Exception {
        // DAO 已给出 displaySales=15；PO 原始字段故意设为 sales=0、fake=null，与计算列值不同
        // 若 Controller 仍在 VO 层 sales+fake 合成，将得到 0，暴露口径漂移
        SkuPO po = fullSkuPo();
        po.setSales(0);
        po.setFakeSales(null);
        po.setDisplaySales(15);
        when(skuService.queryMallPage(any())).thenReturn(new SkuPageResult(Collections.singletonList(po), 1));

        assertEquals(15, fetchFirstItem().getInt("displaySales").intValue(),
                "displaySales 必须来自 DAO 计算列（5.1），而非 VO 层 sales+fake 重新合成（口径漂移）");
    }

    @Test
    void displaySales_不暴露真实与注水分项() throws Exception {
        when(skuService.queryMallPage(any())).thenReturn(new SkuPageResult(Collections.singletonList(fullSkuPo()), 1));

        JSONObject item = fetchFirstItem();
        assertEquals(new HashSet<>(Arrays.asList("skuId", "thumbnail", "name", "price", "displaySales")),
                item.keySet(), "卡片只允许五要素，不得暴露 sales/fakeSales 真实与注水分项");
    }

    // ============ 5.2.5 skuId = po.id ============

    @Test
    void skuId_为po的id供跳转() throws Exception {
        when(skuService.queryMallPage(any())).thenReturn(new SkuPageResult(Collections.singletonList(fullSkuPo()), 1));

        assertEquals(11, fetchFirstItem().getInt("skuId").intValue(),
                "VO 必须含 skuId 且值为 PO.id，供前端构造商品详情跳转链接");
    }

    // ============ 3. 重复创建：同请求重复响应一致 ============

    @Test
    void 同请求重复_响应一致() throws Exception {
        when(skuService.queryMallPage(any())).thenReturn(new SkuPageResult(Collections.singletonList(fullSkuPo()), 1));

        JSONObject first = JSONUtil.parseObj(mockMvc.perform(get("/product/mall/products"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        JSONObject second = JSONUtil.parseObj(mockMvc.perform(get("/product/mall/products"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertEquals(first.toString(), second.toString(), "同请求重复必须返回一致结果");
    }
}
