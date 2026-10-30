package com.ysh.b2cmall.product.web.controller;

import com.ysh.b2cmall.common.response.BaseResponseVO;
import com.ysh.b2cmall.product.component.CreateProductComponentRegistry;
import com.ysh.b2cmall.product.component.CreateProductTemplate;
import com.ysh.b2cmall.product.component.RequestHolderComponent;
import com.ysh.b2cmall.product.dao.po.SkuPO;
import com.ysh.b2cmall.product.service.SkuService;
import com.ysh.b2cmall.product.web.request.CreateProductRequest;
import com.ysh.b2cmall.product.web.request.UpdateProductRequest;
import com.ysh.b2cmall.product.web.vo.ProductDetailVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/product")
public class ProductController {

    @Autowired
    private CreateProductComponentRegistry componentRegistry;

    /** TDD 红阶段占位依赖（功能上下文 2.2）：shopId 一律取登录上下文，忽略请求参数 */
    private final SkuService skuService;

    /** TDD 红阶段占位依赖（功能上下文 2.2）：登录上下文来源 */
    private final RequestHolderComponent requestHolder;

    public ProductController() {
        this(null, null);
    }

    @Autowired
    public ProductController(SkuService skuService, RequestHolderComponent requestHolder) {
        this.skuService = skuService;
        this.requestHolder = requestHolder;
    }

    /**
     * 新建商品
     * 按 type 从注册表取模板（模板方法模式），Controller 不再关心类型映射细节
     */
    @PostMapping("/create")
    public BaseResponseVO<Map<String, Object>> create(@RequestBody @Validated CreateProductRequest req) {
        CreateProductTemplate template = componentRegistry.get(req.getType());
        Integer skuId = template.create(req);

        Map<String, Object> data = new HashMap<>();
        data.put("skuId", skuId);
        return new BaseResponseVO<>(data);
    }

    // ---- 店铺商品管理 TDD 红阶段占位签名（shop-product-list.md 功能上下文 2.1，业务实现待 7.x 补全）----

    /** 商品详情（编辑页）：shopId 隔离，他店/不存在 → 404 */
    @GetMapping("/detail/{skuId}")
    public BaseResponseVO<ProductDetailVO> detail(@PathVariable Integer skuId) {
        SkuPO po = skuService.getDetail(requestHolder.get().getShopId(), skuId);
        ProductDetailVO vo = new ProductDetailVO();
        vo.setSkuId(po.getId());
        vo.setCategoryId(po.getCategoryId());
        vo.setName(po.getName());
        vo.setDescription(po.getDescription());
        vo.setPrice(po.getPrice());
        vo.setMarketPrice(po.getMarketPrice());
        vo.setStock(po.getStock());
        vo.setStoreStock(po.getStoreStock());
        vo.setSales(po.getSales());
        vo.setFakeSales(po.getFakeSales());
        vo.setType(po.getType());
        vo.setProductType(po.getProductType());
        vo.setImages(po.getImages());
        vo.setSpec(po.getSpec());
        vo.setDeliveryMethods(po.getDeliveryMethods());
        vo.setSort(po.getSort());
        vo.setStatus(po.getStatus());
        return new BaseResponseVO<>(vo);
    }

    /** 更新商品：字段校验同导入；不改 sales；他店 skuId → 404 且零改动 */
    @PutMapping("/update")
    public BaseResponseVO<Void> update(@RequestBody(required = false) UpdateProductRequest req) {
        // shopId 恒取登录上下文（2.2）；请求体允许为空以兼容 2.2 隔离契约测试
        UpdateProductRequest body = req == null ? new UpdateProductRequest() : req;
        SkuPO po = new SkuPO();
        po.setId(body.getSkuId());
        po.setCategoryId(body.getCategoryId());
        po.setName(body.getName());
        po.setDescription(body.getDescription());
        po.setPrice(body.getPrice());
        po.setMarketPrice(body.getMarketPrice());
        po.setStock(body.getStock());
        po.setStoreStock(body.getStoreStock());
        po.setFakeSales(body.getFakeSales());
        po.setType(body.getType());
        po.setProductType(body.getProductType());
        po.setImages(body.getImages());
        po.setSpec(body.getSpec());
        po.setDeliveryMethods(body.getDeliveryMethods());
        po.setSort(body.getSort());
        po.setStatus(body.getStatus());
        skuService.updateSku(requestHolder.get().getShopId(), po);
        return new BaseResponseVO<>();
    }

    /** 上架/下架（功能上下文 7.2）：设为目标状态，shopId 隔离，重复操作幂等 */
    @PostMapping("/status")
    public BaseResponseVO<Void> status(@RequestParam(required = false) Integer skuId,
                                       @RequestParam(required = false) Integer status) {
        skuService.updateStatus(requestHolder.get().getShopId(), skuId, status);
        return new BaseResponseVO<>();
    }

    /** 删除商品（功能上下文 7.3.3）：已产生订单禁止删除；shopId 隔离，他店/不存在 → 404 */
    @DeleteMapping("/delete/{skuId}")
    public BaseResponseVO<Void> delete(@PathVariable Integer skuId) {
        skuService.deleteSku(requestHolder.get().getShopId(), skuId);
        return new BaseResponseVO<>();
    }
}
