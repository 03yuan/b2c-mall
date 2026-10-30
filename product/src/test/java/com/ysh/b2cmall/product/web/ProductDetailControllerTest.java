package com.ysh.b2cmall.product.web;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ysh.b2cmall.product.bean.CurrentEmployeeBean;
import com.ysh.b2cmall.product.component.RequestHolderComponent;
import com.ysh.b2cmall.product.dao.po.SkuPO;
import com.ysh.b2cmall.product.service.SkuService;
import com.ysh.b2cmall.product.web.advice.GlobalExceptionHandler;
import com.ysh.b2cmall.product.web.controller.ProductController;
import com.ysh.b2cmall.product.web.interceptor.LoginInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 单品编辑 GET /product/detail/{skuId} + PUT /product/update 的契约测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-product-list.md 功能上下文 7.1：
 * - 7.1.1 详情返回编辑页全部字段（含 images 图文、spec、配送方式、双库存、双销量、排序），
 *   shopId 隔离，他店/不存在 -> 404
 * - 7.1.2 更新基础信息/价格/库存/图文/配送/排序/类型/类别/注水销量；不改 sales；
 *   非法值 -> 400；他店 skuId -> 404 且零改动
 *
 * 用例分类：
 * 1. 字段级校验：详情 VO 17 列齐全且取值正确；更新请求体各字段透传 Service
 * 2. 类型与格式：更新成功 200；Service 业务异常 -> 400 中文消息
 * 3. 重复创建：重复更新请求幂等一致
 * 4. 非法输入：他店/不存在 -> 404 中文消息；越权 shopId 忽略
 *
 * 测试结构：standalone MockMvc + Mockito mock SkuService + 真实 LoginInterceptor + GlobalExceptionHandler。
 */
class ProductDetailControllerTest {

    private RequestHolderComponent requestHolder;
    private SkuService skuService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        requestHolder = mock(RequestHolderComponent.class);
        skuService = mock(SkuService.class);
        when(requestHolder.get()).thenReturn(loginBean(3));
        mockMvc = MockMvcBuilders.standaloneSetup(new ProductController(skuService, requestHolder))
                .addInterceptors(new LoginInterceptor(requestHolder))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private CurrentEmployeeBean loginBean(Integer shopId) {
        CurrentEmployeeBean bean = new CurrentEmployeeBean();
        bean.setEmployeeId(7);
        bean.setShopId(shopId);
        bean.setUsername("tom");
        return bean;
    }

    private SkuPO detailPo() {
        SkuPO po = new SkuPO();
        po.setId(10);
        po.setShopId(3);
        po.setCategoryId(100);
        po.setName("商品A");
        po.setDescription("描述");
        po.setPrice(new BigDecimal("10.00"));
        po.setMarketPrice(new BigDecimal("20.00"));
        po.setStock(7);
        po.setStoreStock(3);
        po.setSales(100);
        po.setFakeSales(5);
        po.setType(1);
        po.setProductType(2);
        po.setImages("a.jpg,b.jpg");
        po.setSpec("规格");
        po.setDeliveryMethods("CITY,EXPRESS");
        po.setSort(9);
        po.setStatus(1);
        return po;
    }

    private String updateBody() {
        return "{\"skuId\":10,\"categoryId\":101,\"name\":\"改名\",\"description\":\"新描述\","
                + "\"price\":9.90,\"marketPrice\":19.90,\"stock\":8,\"storeStock\":4,\"fakeSales\":66,"
                + "\"type\":2,\"productType\":2,\"images\":\"c.jpg\",\"spec\":\"新规格\","
                + "\"deliveryMethods\":\"CITY,PICKUP\",\"sort\":9,\"status\":1}";
    }

    // ============ 1. 字段级校验：详情 VO 17 列齐全且取值正确 ============

    @Test
    void 详情_返回编辑页全部字段且取值正确() throws Exception {
        when(skuService.getDetail(3, 10)).thenReturn(detailPo());

        MvcResult result = mockMvc.perform(get("/product/detail/10")).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(200, response.getStatus(), "详情应返回 200");
        JSONObject body = JSONUtil.parseObj(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        JSONObject data = body.getJSONObject("data");
        assertTrue(data != null, "响应应为 BaseResponseVO JSON（含 data）");

        Set<String> expectedKeys = new HashSet<>(Arrays.asList(
                "skuId", "categoryId", "name", "description", "price", "marketPrice",
                "stock", "storeStock", "sales", "fakeSales", "type", "productType",
                "images", "spec", "deliveryMethods", "sort", "status"));
        assertEquals(expectedKeys, data.keySet(), "详情 VO 必须恰好含编辑页 17 列（含双库存、双销量、图文、规格、排序）");

        assertEquals(10, data.getInt("skuId").intValue(), "skuId 应正确");
        assertEquals("商品A", data.getStr("name"), "名称应正确");
        assertEquals("a.jpg,b.jpg", data.getStr("images"), "图文应为整串（编辑页需全部图）");
        assertEquals("规格", data.getStr("spec"), "规格应正确");
        assertEquals(100, data.getInt("sales").intValue(), "真实销量应正确");
        assertEquals(5, data.getInt("fakeSales").intValue(), "注水销量应正确");
        assertEquals(7, data.getInt("stock").intValue(), "仓库库存应正确");
        assertEquals(3, data.getInt("storeStock").intValue(), "门店库存应正确");
        assertEquals("CITY,EXPRESS", data.getStr("deliveryMethods"), "配送方式应正确");
        assertEquals(9, data.getInt("sort").intValue(), "排序应正确");
    }

    // ============ 4. 非法输入：他店/不存在 -> 404 ============

    @Test
    void 详情_他店或不存在_返回404() throws Exception {
        when(skuService.getDetail(3, 20)).thenThrow(new IllegalArgumentException("商品不存在"));

        MvcResult result = mockMvc.perform(get("/product/detail/20")).andReturn();
        MockHttpServletResponse response = result.getResponse();

        assertEquals(404, response.getStatus(), "他店/不存在应返回 404");
        assertTrue(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("不存在"),
                "404 消息应含「不存在」");
    }

    // ============ 1. 字段级校验：更新请求体各字段透传 Service ============

    @Test
    void 更新_请求体字段原样透传Service() throws Exception {
        mockMvc.perform(put("/product/update")
                        .contentType("application/json")
                        .content(updateBody()))
                .andReturn();

        ArgumentCaptor<SkuPO> captor = ArgumentCaptor.forClass(SkuPO.class);
        verify(skuService).updateSku(eq(3), captor.capture());
        SkuPO po = captor.getValue();
        assertEquals(10, po.getId().intValue(), "skuId 应透传");
        assertEquals(101, po.getCategoryId().intValue(), "categoryId 应透传");
        assertEquals("改名", po.getName(), "name 应透传");
        assertEquals("新描述", po.getDescription(), "description 应透传");
        assertEquals(0, new BigDecimal("9.90").compareTo(po.getPrice()), "price 应透传");
        assertEquals(0, new BigDecimal("19.90").compareTo(po.getMarketPrice()), "marketPrice 应透传");
        assertEquals(8, po.getStock().intValue(), "stock 应透传");
        assertEquals(4, po.getStoreStock().intValue(), "storeStock 应透传");
        assertEquals(66, po.getFakeSales().intValue(), "fakeSales 应透传");
        assertEquals(2, po.getType().intValue(), "type 应透传");
        assertEquals(2, po.getProductType().intValue(), "productType 应透传");
        assertEquals("c.jpg", po.getImages(), "images 应透传");
        assertEquals("新规格", po.getSpec(), "spec 应透传");
        assertEquals("CITY,PICKUP", po.getDeliveryMethods(), "deliveryMethods 应透传");
        assertEquals(9, po.getSort().intValue(), "sort 应透传");
        assertEquals(1, po.getStatus().intValue(), "status 应透传");
    }

    // ============ 2. 类型与格式：更新成功 200 + 业务异常 -> 400/404 ============

    @Test
    void 更新_成功返回200() throws Exception {
        MvcResult result = mockMvc.perform(put("/product/update")
                        .contentType("application/json")
                        .content(updateBody()))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus(), "更新成功应返回 200");
        JSONObject body = JSONUtil.parseObj(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(body.containsKey("status"), "响应应为 BaseResponseVO JSON");
    }

    @Test
    void 更新_业务校验失败_返回400中文消息() throws Exception {
        doThrow(new IllegalArgumentException("库存必须为大于等于 0 的整数"))
                .when(skuService).updateSku(eq(3), any());

        MvcResult result = mockMvc.perform(put("/product/update")
                        .contentType("application/json")
                        .content(updateBody()))
                .andReturn();

        assertEquals(400, result.getResponse().getStatus(), "业务校验失败应返回 400");
        assertTrue(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("库存"),
                "400 消息应含中文原因");
    }

    @Test
    void 更新_他店skuId_返回404() throws Exception {
        doThrow(new IllegalArgumentException("商品不存在"))
                .when(skuService).updateSku(eq(3), any());

        MvcResult result = mockMvc.perform(put("/product/update")
                        .contentType("application/json")
                        .content(updateBody()))
                .andReturn();

        assertEquals(404, result.getResponse().getStatus(), "他店 skuId 应返回 404");
        assertTrue(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("不存在"),
                "404 消息应含「不存在」");
    }

    // ============ 3. 重复创建：重复更新请求幂等一致 ============

    @Test
    void 重复更新_响应一致() throws Exception {
        String first = mockMvc.perform(put("/product/update")
                        .contentType("application/json").content(updateBody()))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String second = mockMvc.perform(put("/product/update")
                        .contentType("application/json").content(updateBody()))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertEquals(first, second, "重复更新响应必须一致");
        assertTrue(first.contains("\"status\""), "响应应为 BaseResponseVO JSON（含 status）");
        verify(skuService, org.mockito.Mockito.times(2)).updateSku(eq(3), any());
    }

    // ============ 4. 非法输入：越权 shopId 忽略 ============

    @Test
    void 更新_越权shopId参数被忽略() throws Exception {
        mockMvc.perform(put("/product/update")
                        .contentType("application/json")
                        .content(updateBody())
                        .param("shopId", "99"))
                .andReturn();

        verify(skuService).updateSku(eq(3), any());
    }
}
