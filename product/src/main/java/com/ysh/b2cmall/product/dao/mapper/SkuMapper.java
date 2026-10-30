package com.ysh.b2cmall.product.dao.mapper;

import com.ysh.b2cmall.product.dao.po.MallSkuQuery;
import com.ysh.b2cmall.product.dao.po.SkuPO;
import com.ysh.b2cmall.product.dao.po.SkuQuery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SkuMapper {

    Integer insert(SkuPO po);

    SkuPO selectById(@Param("id") Integer id);

    int updateStatus(@Param("id") Integer id, @Param("status") Integer status);

    /** 库存预警计数：库存低于自身阈值的 sku 数（TDD 红阶段空签名，SQL 待补充） */
    int countStockAlertByShopId(@Param("shopId") Integer shopId);

    // ---- 店铺商品管理 TDD 红阶段占位签名（shop-product-list.md 功能上下文 2.2，SQL 待绿阶段补充）----

    /** 按 id + shopId 更新状态（SQL 必须恒带 shop_id 条件） */
    int updateStatusWithinShop(@Param("id") Integer id, @Param("status") Integer status,
                               @Param("shopId") Integer shopId);

    /** 按 id + shopId 删除（SQL 必须恒带 shop_id 条件） */
    int deleteWithinShop(@Param("id") Integer id, @Param("shopId") Integer shopId);

    /** 按 id + shopId 查询（SQL 必须恒带 shop_id 条件，跨店查不到） */
    SkuPO selectByIdWithinShop(@Param("id") Integer id, @Param("shopId") Integer shopId);

    /** 按 shopId 查询本店 sku（SQL 必须恒带 shop_id 条件） */
    List<SkuPO> selectByShopId(@Param("shopId") Integer shopId);

    /** 按 id + shopId 更新（SQL 必须恒带 shop_id 条件） */
    int updateSkuWithinShop(@Param("po") SkuPO po, @Param("shopId") Integer shopId);

    // ---- 商品列表动态条件分页查询 TDD 红阶段占位签名（shop-product-list.md 功能上下文 3.1，SQL 待绿阶段补充）----

    /** 动态条件分页查询（shopId 恒带；sort DESC, id DESC 稳定排序；按 page/size 分页） */
    List<SkuPO> selectPage(SkuQuery query);

    /** 条件命中总数（total=筛选命中总数，不受分页影响） */
    int countByFilter(SkuQuery query);

    // ---- 批量设置 TDD 红阶段占位签名（shop-product-list.md 功能上下文 4.1，SQL 待绿阶段补充）----

    /**
     * 按 ids 批量动态更新（只回写 po 中非空字段，统一设为固定值）；
     * SQL 必须恒带 shop_id 条件，ids 中的他店 id 不生效。
     */
    int batchUpdateWithinShop(@Param("po") SkuPO po, @Param("ids") List<Integer> ids,
                              @Param("shopId") Integer shopId);

    // ---- 导出商品列表 TDD 红阶段占位签名（shop-product-list.md 功能上下文 5.1，SQL 待绿阶段补充）----

    /**
     * 按筛选条件取全量（不分页）数据行，与列表共用同一筛选构建器与排序；
     * shopId 恒带隔离，导出绝不返回条件外或他店商品。
     */
    List<SkuPO> selectAllByFilter(SkuQuery query);

    // ---- 导入商品 Excel（功能上下文 6.1）----

    /**
     * 导入更新（恒带 id + shop_id 双条件）：只更新导入模板列对应字段，
     * 不动 sales（真实销量以 DB/事件为准）、images、spec、category_id。
     */
    int updateImportWithinShop(@Param("po") SkuPO po, @Param("shopId") Integer shopId);

    // ---- 真实销量回写（功能上下文 8.1，TDD 红阶段占位签名）----

    /** 销量累加：sales = sales + quantity（累加非覆盖） */
    int incrementSales(@Param("skuId") Integer skuId, @Param("quantity") Integer quantity);

    /** 消费幂等去重：记录已处理 orderId（tb_processed_event，主键冲突返回 0 表示重复投递） */
    int insertProcessedEvent(@Param("orderId") Integer orderId);

    // ---- C 端商品卡片列表（功能上下文 2.2，TDD 红阶段占位签名，SQL 待绿阶段补充）----

    /** C 端 mall 分页查询（恒强制 status=1、不带 shop_id；categoryName 可选跨店过滤） */
    List<SkuPO> selectMallPage(MallSkuQuery query);

    /** C 端 mall 条件命中总数（total 不受分页影响） */
    int countMallByFilter(MallSkuQuery query);
}
