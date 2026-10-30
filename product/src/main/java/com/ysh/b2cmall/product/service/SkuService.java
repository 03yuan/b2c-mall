package com.ysh.b2cmall.product.service;

import com.ysh.b2cmall.product.dao.po.SkuPO;
import com.ysh.b2cmall.product.dao.po.SkuPageResult;
import com.ysh.b2cmall.product.dao.po.SkuQuery;
import com.ysh.b2cmall.product.dao.po.MallSkuQuery;

import java.util.List;

public interface SkuService {

    /**
     * 保存商品信息（返回自增ID）
     */
    Integer saveSku(SkuPO po);

    /**
     * 上架
     */
    void putOnSale(Integer skuId);

    // ---- 店铺商品管理 TDD 红阶段占位签名（shop-product-list.md 功能上下文 2.2）----
    // shopId 一律取登录上下文（RequestHolderComponent），请求参数中的 shopId 必须忽略

    /** 商品列表查询（shopId 恒取登录上下文） */
    List<SkuPO> queryList(Integer shopId);

    // ---- 商品列表动态条件分页查询（功能上下文 3.2）----

    /** 动态条件分页查询（query.shopId 恒取登录上下文；返回当前页行 + 条件命中总数） */
    SkuPageResult queryPage(SkuQuery query);

    /** 更新商品（shopId 恒取登录上下文） */
    void updateSku(Integer shopId, SkuPO po);

    /** 上架/下架（shopId 恒取登录上下文） */
    void updateStatus(Integer shopId, Integer skuId, Integer status);

    /** 删除商品（shopId 恒取登录上下文） */
    void deleteSku(Integer shopId, Integer skuId);

    // ---- 批量设置（功能上下文 4.1）----

    /**
     * 批量更新（按操作类型分发）：
     * action = status / deliveryMethods / categoryId / sort / stock / storeStock；
     * value 为该操作的目标值（统一设为固定值，非增减）；恒带 shopId 隔离，他店 id 计入 skipped。
     */
    BatchUpdateResult batchUpdate(Integer shopId, List<Integer> skuIds, String action, String value);

    // ---- 导出商品列表（功能上下文 5.1）----

    /** 导出数据组装：与列表共用同一筛选，取全量（不分页）数据行（query.shopId 恒取登录上下文） */
    List<SkuPO> exportList(SkuQuery query);

    // ---- 导入商品 Excel TDD 红阶段占位签名（shop-product-list.md 功能上下文 6.1，待绿阶段补全）----

    /**
     * 导入 Excel（批量新增/更新，按商品ID 分流）：
     * ID 空 → 新增；ID 存在且属本店 → 更新；ID 不存在或属他店 → 该行报错；
     * 逐行字段校验，错误行收集「行号+原因」，好行照常入库；sales 以 DB 为准不被 Excel 覆盖。
     */
    ImportResult importExcel(Integer shopId, java.io.InputStream inputStream);

    // ---- 单品编辑 TDD 红阶段占位签名（shop-product-list.md 功能上下文 7.1，待绿阶段补全）----

    /** 商品详情（编辑页）：shopId 隔离，不存在或属他店 → 抛 IllegalArgumentException（消息含「不存在」→ 404） */
    SkuPO getDetail(Integer shopId, Integer skuId);

    // ---- C 端商品卡片列表（功能上下文 2.3，TDD 红阶段占位签名，实现待绿阶段补全）----

    /** C 端 mall 分页查询（公开免登录，恒 status=1、跨店聚合；返回当前页行 + 条件命中总数） */
    SkuPageResult queryMallPage(MallSkuQuery query);
}
