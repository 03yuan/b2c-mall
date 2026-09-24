package com.ysh.b2cmall.product.component;

import cn.hutool.core.lang.Assert;
import com.ysh.b2cmall.product.bean.CurrentEmployeeBean;
import com.ysh.b2cmall.product.dao.mapper.CategoryMapper;
import com.ysh.b2cmall.product.dao.po.CategoryPO;
import com.ysh.b2cmall.product.dao.po.SkuPO;
import com.ysh.b2cmall.product.service.SkuLogService;
import com.ysh.b2cmall.product.service.SkuService;
import com.ysh.b2cmall.product.web.request.CreateProductRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

/**
 * 新建商品模板方法模式骨架。
 * 固定执行顺序：参数校验 → 类目归属校验 → 价格库存校验 → 内容合规审核 → 保存商品 → 上架 → 后置处理
 * 子类实现差异化步骤：价格库存校验、内容合规审核。
 */
@Slf4j
public abstract class CreateProductTemplate {

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private SkuService skuService;

    @Autowired
    private SkuLogService skuLogService;

    @Autowired
    private RequestHolderComponent requestHolder;

    /**
     * 模板方法（final，子类不可重写执行顺序）
     *
     * @return 新建商品ID
     */
    public final Integer create(CreateProductRequest req) {
        // 1. 参数校验
        validateParams(req);

        // 2. 类目归属校验
        validateCategory(req);

        // 3. 价格库存校验（子类实现）
        validatePriceAndStock(req);

        // 4. 内容合规审核（子类实现）
        auditContent(req);

        // 5. 保存商品信息
        Integer skuId = saveProduct(req);

        // 6. 上架
        putOnSale(skuId);

        // 7. 后置处理（写操作日志）
        postProcess(skuId, req);

        return skuId;
    }

    // ===================== 通用步骤 =====================

    /**
     * 1. 参数校验（通用，子类可覆盖做更严格校验）
     */
    protected void validateParams(CreateProductRequest req) {
        Assert.notNull(req.getShopId(), "店铺ID不能为空");
        Assert.notNull(req.getCategoryId(), "类目ID不能为空");
        Assert.notBlank(req.getName(), "商品名称不能为空");
        Assert.notNull(req.getPrice(), "价格不能为空");
        Assert.isTrue(req.getPrice().compareTo(BigDecimal.ZERO) > 0, "价格必须大于0");
        Assert.notNull(req.getType(), "商品类型不能为空");
    }

    /**
     * 2. 类目校验（通用）：类目必须存在且状态正常（平台级类目，所有店铺共用）
     */
    protected void validateCategory(CreateProductRequest req) {
        CategoryPO category = categoryMapper.selectById(req.getCategoryId());
        Assert.notNull(category, "类目不存在");
    }

    /**
     * 5. 保存商品信息（通用）
     */
    protected Integer saveProduct(CreateProductRequest req) {
        SkuPO po = new SkuPO();
        BeanUtils.copyProperties(req, po);
        po.setStatus(0); // 先保存为下架状态
        Integer skuId = skuService.saveSku(po);
        log.info("商品已保存，skuId={}", skuId);
        return skuId;
    }

    /**
     * 6. 上架（通用）
     */
    protected void putOnSale(Integer skuId) {
        skuService.putOnSale(skuId);
        log.info("商品已上架，skuId={}", skuId);
    }

    /**
     * 7. 后置处理（钩子，默认写操作日志，子类可覆盖扩展）
     */
    protected void postProcess(Integer skuId, CreateProductRequest req) {
        CurrentEmployeeBean current = requestHolder.get();
        Integer operatorId = current != null ? current.getEmployeeId() : null;
        String content = String.format("创建商品：%s，价格：%s，类型：%s",
                req.getName(), req.getPrice(), req.getType() == 1 ? "实物" : "虚拟");
        skuLogService.log(skuId, operatorId, "CREATE", content);
        log.info("商品操作日志已写入，skuId={}", skuId);
    }

    // ===================== 抽象步骤（子类实现） =====================

    /**
     * 3. 价格库存校验（实物/虚拟差异化）
     */
    protected abstract void validatePriceAndStock(CreateProductRequest req);

    /**
     * 4. 内容合规审核（实物/虚拟差异化）
     */
    protected abstract void auditContent(CreateProductRequest req);
}
