package com.ysh.b2cmall.product.service.impl;

import cn.hutool.core.lang.Assert;
import com.alibaba.excel.EasyExcel;
import com.ysh.b2cmall.common.response.BaseResponseVO;
import com.ysh.b2cmall.product.service.DownstreamServiceException;
import com.ysh.b2cmall.product.dao.mapper.CategoryMapper;
import com.ysh.b2cmall.product.dao.mapper.SkuMapper;
import com.ysh.b2cmall.product.dao.po.CategoryPO;
import com.ysh.b2cmall.product.dao.po.MallSkuQuery;
import com.ysh.b2cmall.product.dao.po.SkuPO;
import com.ysh.b2cmall.product.dao.po.SkuPageResult;
import com.ysh.b2cmall.product.dao.po.SkuQuery;
import com.ysh.b2cmall.product.service.BatchUpdateResult;
import com.ysh.b2cmall.product.service.ImportResult;
import com.ysh.b2cmall.product.service.SkuService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
public class SkuServiceImpl implements SkuService {

    @Autowired
    private SkuMapper skuMapper;

    /** 批量设置分类时校验 categoryId 属于本店（功能上下文 4.1） */
    @Autowired
    private CategoryMapper categoryMapper;

    /** 删除前查询订单计数（功能上下文 7.3.3，TDD 红阶段预置依赖，调用逻辑待绿阶段补全） */
    @Autowired
    private com.ysh.b2cmall.product.client.OrderQueryClient orderQueryClient;

    @Override
    public Integer saveSku(SkuPO po) {
        int row = skuMapper.insert(po);
        Assert.equals(row, 1, "保存商品失败");
        return po.getId();
    }

    @Override
    public void putOnSale(Integer skuId) {
        int row = skuMapper.updateStatus(skuId, 1);
        Assert.equals(row, 1, "上架失败");
    }

    // ---- 店铺商品管理：shopId 强制隔离（功能上下文 2.2）：shopId 恒取登录上下文并透传到 DAO，SQL 恒带 shop_id 条件 ----

    @Override
    public java.util.List<SkuPO> queryList(Integer shopId) {
        return skuMapper.selectByShopId(shopId);
    }

    // ---- 商品列表动态条件分页查询（功能上下文 3.2）：当前页行 + 条件命中总数一次带回 ----

    @Override
    public SkuPageResult queryPage(SkuQuery query) {
        List<SkuPO> rows = skuMapper.selectPage(query);
        int total = skuMapper.countByFilter(query);
        return new SkuPageResult(rows, total);
    }

    @Override
    public void updateSku(Integer shopId, SkuPO po) {
        // 功能上下文 7.1.2：存在性校验（他店/不存在 -> 「不存在」-> 404）+ 字段校验（同导入口径）；
        // 更新 SQL 不含 shop_id（防搬店）、sales（真实销量只由 MQ 回写）、status（走上下架接口）
        Assert.notNull(po.getId(), "商品ID不能为空");
        SkuPO existing = skuMapper.selectByIdWithinShop(po.getId(), shopId);
        Assert.notNull(existing, "商品不存在");
        validateUpdate(po);
        skuMapper.updateSkuWithinShop(po, shopId);
    }

    /** 更新字段校验（功能上下文 7.1.2，与导入 6.1.1 同口径；null 字段跳过以支持部分更新） */
    private void validateUpdate(SkuPO po) {
        if (po.getName() != null) {
            Assert.isTrue(!po.getName().trim().isEmpty(), "商品名称不能为空");
        }
        if (po.getPrice() != null) {
            Assert.isTrue(po.getPrice().signum() >= 0, "销售价必须为非负数字");
        }
        if (po.getMarketPrice() != null) {
            Assert.isTrue(po.getMarketPrice().signum() >= 0, "市场价必须为非负数字");
        }
        if (po.getStock() != null) {
            Assert.isTrue(po.getStock() >= 0, "仓库库存必须为大于等于 0 的整数");
        }
        if (po.getStoreStock() != null) {
            Assert.isTrue(po.getStoreStock() >= 0, "门店库存必须为大于等于 0 的整数");
        }
        if (po.getFakeSales() != null) {
            Assert.isTrue(po.getFakeSales() >= 0, "注水销量必须为大于等于 0 的整数");
        }
        if (po.getProductType() != null) {
            Assert.isTrue(PRODUCT_TYPES.contains(po.getProductType()), "商品类型必须为 1（普通）或 2（秒杀）");
        }
        if (po.getType() != null) {
            Assert.isTrue(SKU_TYPES.contains(po.getType()), "商品类别必须为 1（实物）/2（虚拟）/3（卡券）");
        }
        if (po.getStatus() != null) {
            Assert.isTrue(SKU_STATUSES.contains(po.getStatus()), "状态必须为 0（下架）或 1（上架）");
        }
        if (po.getDeliveryMethods() != null) {
            parseDeliveryMethods(po.getDeliveryMethods());
        }
    }

    @Override
    public void updateStatus(Integer shopId, Integer skuId, Integer status) {
        // 功能上下文 7.2.1：参数校验 + 存在性校验（他店/不存在 -> 「不存在」-> 404）；
        // 双向切换（1->0、0->1），重复设同状态幂等成功；SQL 恒带 shop_id（2.2）
        Assert.notNull(skuId, "商品ID不能为空");
        Assert.notNull(status, "状态不能为空");
        Assert.isTrue(SKU_STATUSES.contains(status), "状态必须为 0（下架）或 1（上架）");
        SkuPO existing = skuMapper.selectByIdWithinShop(skuId, shopId);
        Assert.notNull(existing, "商品不存在");
        skuMapper.updateStatusWithinShop(skuId, status, shopId);
    }

    @Override
    public void deleteSku(Integer shopId, Integer skuId) {
        // 功能上下文 7.3.3：存在性校验先行（他店/不存在 -> 「不存在」-> 404，不浪费跨服务调用）；
        // 订单计数 > 0 -> 拒删；= 0 -> 删除；SQL 恒带 shop_id（2.2）
        SkuPO existing = skuMapper.selectByIdWithinShop(skuId, shopId);
        Assert.notNull(existing, "商品不存在");
        int orderCount = queryOrderCount(skuId);
        Assert.isTrue(orderCount == 0, "已产生订单的商品禁止删除");
        skuMapper.deleteWithinShop(skuId, shopId);
    }

    /** 7.3.4：下游故障/响应契约破坏统一转译为 503 拒删，宁可拒绝删除也不放行 */
    private int queryOrderCount(Integer skuId) {
        BaseResponseVO<Map<String, Object>> response;
        try {
            response = orderQueryClient.countBySku(skuId);
        } catch (RuntimeException e) {
            throw new DownstreamServiceException("订单服务暂不可用，请稍后重试", e);
        }
        if (response == null || response.getData() == null
                || !(response.getData().get("count") instanceof Number)) {
            throw new DownstreamServiceException("订单服务暂不可用，请稍后重试");
        }
        return ((Number) response.getData().get("count")).intValue();
    }

    // ---- 批量设置（功能上下文 4.1）：按操作类型分发，只回写目标字段，统一设为固定值 ----

    @Override
    public BatchUpdateResult batchUpdate(Integer shopId, List<Integer> skuIds, String action, String value) {
        Assert.isTrue(skuIds != null && !skuIds.isEmpty(), "请选择要设置的商品");
        Assert.isTrue(action != null && !action.trim().isEmpty(), "不支持的批量操作类型：{}", action);

        SkuPO patch = new SkuPO();
        switch (action) {
            case "status":
                patch.setStatus(parseInt(value, "状态"));
                break;
            case "deliveryMethods":
                Assert.notBlank(value, "配送方式不能为空");
                patch.setDeliveryMethods(value.trim());
                break;
            case "categoryId":
                Integer categoryId = parseInt(value, "分类");
                boolean belongs = categoryMapper.selectEnabledByShopId(shopId).stream()
                        .anyMatch(category -> categoryId.equals(category.getId()));
                Assert.isTrue(belongs, "商品分类不存在");
                patch.setCategoryId(categoryId);
                break;
            case "sort":
                patch.setSort(parseInt(value, "排序"));
                break;
            case "stock":
                patch.setStock(parseNonNegativeInt(value, "库存"));
                break;
            case "storeStock":
                patch.setStoreStock(parseNonNegativeInt(value, "门店库存"));
                break;
            default:
                throw new IllegalArgumentException("不支持的批量操作类型：" + action);
        }

        int affected = skuMapper.batchUpdateWithinShop(patch, skuIds, shopId);
        return new BatchUpdateResult(affected, skuIds.size() - affected);
    }

    private Integer parseInt(String value, String label) {
        try {
            return Integer.valueOf(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "必须为整数");
        }
    }

    private Integer parseNonNegativeInt(String value, String label) {
        Integer parsed = parseInt(value, label);
        if (parsed < 0) {
            throw new IllegalArgumentException(label + "必须为大于等于 0 的整数");
        }
        return parsed;
    }

    // ---- 导出商品列表（功能上下文 5.1）：与列表共用同一筛选，取全量不分页 ----

    @Override
    public List<SkuPO> exportList(SkuQuery query) {
        return skuMapper.selectAllByFilter(query);
    }

    // ---- 导入商品 Excel（功能上下文 6.1）：解析、逐行校验、按 ID 分流、sales 保护 ----

    private static final List<Integer> PRODUCT_TYPES = Arrays.asList(1, 2);
    private static final List<Integer> SKU_TYPES = Arrays.asList(1, 2, 3);
    private static final List<Integer> SKU_STATUSES = Arrays.asList(0, 1);
    private static final List<String> DELIVERY_METHOD_ENUMS = Arrays.asList("CITY", "PICKUP", "EXPRESS");

    @Override
    public ImportResult importExcel(Integer shopId, InputStream inputStream) {
        List<Map<Integer, String>> rows = EasyExcel.read(inputStream).sheet(0).doReadSync();
        ImportResult result = ImportResult.empty();
        int excelRow = 1; // 第 1 行为表头，数据行从第 2 行起
        for (Map<Integer, String> row : rows) {
            excelRow++;
            try {
                SkuPO po = parseImportRow(row);
                String idText = col(row, 0);
                if (idText.isEmpty()) {
                    // ID 空 -> 新增：shopId 取登录上下文，分类默认 0（未分类），sales 强制 0（保护）
                    po.setShopId(shopId);
                    po.setCategoryId(0);
                    po.setSales(0);
                    skuMapper.insert(po);
                    result.setInserted(result.getInserted() + 1);
                } else {
                    // ID 非空 -> 仅当存在且属本店才更新；sales 不动（updateImportWithinShop 不含 sales）
                    Integer id = parseImportInt(idText, "商品ID");
                    SkuPO existing = skuMapper.selectById(id);
                    if (existing == null || !shopId.equals(existing.getShopId())) {
                        throw new IllegalArgumentException("商品ID 不存在");
                    }
                    po.setId(id);
                    skuMapper.updateImportWithinShop(po, shopId);
                    result.setUpdated(result.getUpdated() + 1);
                }
            } catch (IllegalArgumentException e) {
                // 逐行独立：错误行收集「行号+原因」，不连坐回滚好行
                result.getErrors().add("第" + excelRow + "行：" + e.getMessage());
            }
        }
        return result;
    }

    /** 解析并校验一行导入数据（列序与 ProductExcelColumns.HEADERS 一一对应） */
    private SkuPO parseImportRow(Map<Integer, String> row) {
        SkuPO po = new SkuPO();
        po.setName(parseRequired(col(row, 1), "商品名称"));
        po.setMarketPrice(parseOptionalPrice(col(row, 2), "市场价"));
        po.setPrice(parseRequiredPrice(col(row, 3)));
        po.setFakeSales(parseNonNegativeImportInt(col(row, 5), "注水销量"));
        po.setStock(parseNonNegativeImportInt(col(row, 6), "仓库库存"));
        po.setStoreStock(parseNonNegativeImportInt(col(row, 7), "门店库存"));
        po.setProductType(parseEnum(col(row, 8), PRODUCT_TYPES, "商品类型必须为 1（普通）或 2（秒杀）", true));
        po.setType(parseEnum(col(row, 9), SKU_TYPES, "商品类别必须为 1（实物）/2（虚拟）/3（卡券）", true));
        po.setStatus(parseEnum(col(row, 10), SKU_STATUSES, "状态必须为 0（下架）或 1（上架）", true));
        po.setDeliveryMethods(parseDeliveryMethods(col(row, 11)));
        po.setSort(parseNonNegativeImportInt(col(row, 12), "排序"));
        return po;
    }

    private String col(Map<Integer, String> row, int index) {
        String value = row.get(index);
        return value == null ? "" : value.trim();
    }

    private String parseRequired(String value, String label) {
        Assert.isTrue(!value.isEmpty(), label + "不能为空");
        return value;
    }

    private java.math.BigDecimal parseRequiredPrice(String value) {
        Assert.isTrue(!value.isEmpty(), "销售价不能为空");
        return parsePriceValue(value, "销售价");
    }

    private java.math.BigDecimal parseOptionalPrice(String value, String label) {
        if (value.isEmpty()) {
            return null;
        }
        return parsePriceValue(value, label);
    }

    private java.math.BigDecimal parsePriceValue(String value, String label) {
        try {
            java.math.BigDecimal price = new java.math.BigDecimal(value);
            Assert.isTrue(price.compareTo(java.math.BigDecimal.ZERO) >= 0, label + "必须为非负数字");
            return price;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "必须为非负数字");
        }
    }

    private Integer parseImportInt(String value, String label) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "必须为整数");
        }
    }

    private Integer parseNonNegativeImportInt(String value, String label) {
        if (value.isEmpty()) {
            return 0;
        }
        Integer parsed = parseImportInt(value, label);
        if (parsed < 0) {
            throw new IllegalArgumentException(label + "必须为大于等于 0 的整数");
        }
        return parsed;
    }

    private Integer parseEnum(String value, List<Integer> allowed, String message, boolean required) {
        if (value.isEmpty()) {
            Assert.isTrue(!required, message);
            return null;
        }
        Integer parsed = parseImportInt(value, message);
        Assert.isTrue(allowed.contains(parsed), message);
        return parsed;
    }

    private String parseDeliveryMethods(String value) {
        if (value.isEmpty()) {
            return null;
        }
        for (String item : value.split(",")) {
            Assert.isTrue(DELIVERY_METHOD_ENUMS.contains(item.trim()), "配送方式必须为 CITY/PICKUP/EXPRESS");
        }
        return value;
    }

    // ---- 单品编辑（功能上下文 7.1）：详情 shopId 隔离，不存在/他店 -> 「不存在」（-> 404）----

    @Override
    public SkuPO getDetail(Integer shopId, Integer skuId) {
        SkuPO po = skuMapper.selectByIdWithinShop(skuId, shopId);
        Assert.notNull(po, "商品不存在");
        return po;
    }

    // ---- C 端商品卡片列表（功能上下文 2.3）：公开免登录，恒 status=1、跨店聚合 ----

    @Override
    public SkuPageResult queryMallPage(MallSkuQuery query) {
        List<SkuPO> rows = skuMapper.selectMallPage(query);
        int total = skuMapper.countMallByFilter(query);
        return new SkuPageResult(rows, total);
    }
}
