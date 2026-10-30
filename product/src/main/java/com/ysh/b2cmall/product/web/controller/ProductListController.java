package com.ysh.b2cmall.product.web.controller;

import com.ysh.b2cmall.common.response.BaseResponseVO;
import com.ysh.b2cmall.product.component.RequestHolderComponent;
import com.ysh.b2cmall.product.dao.po.CategoryPO;
import com.ysh.b2cmall.product.dao.po.SkuPO;
import com.ysh.b2cmall.product.dao.po.SkuPageResult;
import com.ysh.b2cmall.product.dao.po.SkuQuery;
import com.ysh.b2cmall.product.service.CategoryService;
import com.ysh.b2cmall.product.service.SkuService;
import com.ysh.b2cmall.product.web.vo.CategoryOptionVO;
import com.ysh.b2cmall.product.web.vo.ProductItemVO;
import com.ysh.b2cmall.product.web.vo.ProductListVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 商品列表工作台入口：分页 + 动态筛选 + 行 VO 组装
 */
@RestController
@RequestMapping("/product")
public class ProductListController {

    /** TDD 红阶段占位依赖（功能上下文 2.2）：shopId 一律取登录上下文，忽略请求参数 */
    private final SkuService skuService;

    /** TDD 红阶段占位依赖（功能上下文 2.2）：登录上下文来源 */
    private final RequestHolderComponent requestHolder;

    /** 商品分类下拉（功能上下文 3.3） */
    private final CategoryService categoryService;

    public ProductListController() {
        this(null, null, null);
    }

    @Autowired
    public ProductListController(SkuService skuService, RequestHolderComponent requestHolder,
                                 CategoryService categoryService) {
        this.skuService = skuService;
        this.requestHolder = requestHolder;
        this.categoryService = categoryService;
    }

    /** 商品列表（分页 + 筛选） */
    @GetMapping("/list")
    public BaseResponseVO<ProductListVO> list(@RequestParam(required = false) String name,
                                              @RequestParam(required = false) Integer productType,
                                              @RequestParam(required = false) Integer type,
                                              @RequestParam(required = false) Integer status,
                                              @RequestParam(required = false) Integer categoryId,
                                              @RequestParam(required = false) List<String> deliveryMethods,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        SkuQuery query = new SkuQuery();
        // shopId 恒取登录上下文，请求参数中的 shopId 一律忽略
        query.setShopId(requestHolder.get().getShopId());
        query.setName(name);
        query.setProductType(productType);
        query.setType(type);
        query.setStatus(status);
        query.setCategoryId(categoryId);
        query.setDeliveryMethods(deliveryMethods);
        // page/size 非法（越界）由 SkuQuery setter 抛出，经全局异常处理转 400
        if (page != null) {
            query.setPage(page);
        }
        if (size != null) {
            query.setSize(size);
        }

        SkuPageResult pageResult = skuService.queryPage(query);
        return new BaseResponseVO<>(toListVO(pageResult));
    }

    /** 分页结果 -> 列表 VO；空结果为空列表 + total=0，不伪造任何数值 */
    private ProductListVO toListVO(SkuPageResult pageResult) {
        List<SkuPO> rows = pageResult.getRows() == null
                ? Collections.emptyList() : pageResult.getRows();
        List<ProductItemVO> items = new ArrayList<>(rows.size());
        for (SkuPO po : rows) {
            items.add(toItemVO(po));
        }
        ProductListVO vo = new ProductListVO();
        vo.setList(items);
        vo.setTotal(pageResult.getTotal());
        return vo;
    }

    private ProductItemVO toItemVO(SkuPO po) {
        ProductItemVO item = new ProductItemVO();
        item.setSkuId(po.getId());
        item.setThumbnail(firstImage(po.getImages()));
        item.setName(po.getName());
        item.setMarketPrice(po.getMarketPrice());
        item.setPrice(po.getPrice());
        item.setSales(po.getSales());
        item.setFakeSales(po.getFakeSales());
        item.setStock(po.getStock());
        item.setStoreStock(po.getStoreStock());
        item.setProductType(po.getProductType());
        item.setType(po.getType());
        item.setStatus(po.getStatus());
        item.setDeliveryMethods(po.getDeliveryMethods());
        item.setSort(po.getSort());
        return item;
    }

    /** 缩略图取 images 第一张图（images 为逗号分隔多图，不返回整串）；无图返回 null 不伪造 */
    private String firstImage(String images) {
        if (images == null || images.trim().isEmpty()) {
            return null;
        }
        int commaIndex = images.indexOf(',');
        String first = commaIndex < 0 ? images : images.substring(0, commaIndex);
        return first.trim();
    }

    /** 商品分类下拉（当前店铺启用类目，供筛选与批量设置） */
    @GetMapping("/categories")
    public BaseResponseVO<List<CategoryOptionVO>> categories() {
        // shopId 恒取登录上下文，请求参数中的 shopId 一律忽略
        List<CategoryPO> categories = categoryService.listEnabledCategories(requestHolder.get().getShopId());
        List<CategoryOptionVO> options = new ArrayList<>(categories.size());
        for (CategoryPO category : categories) {
            CategoryOptionVO option = new CategoryOptionVO();
            option.setId(category.getId());
            option.setName(category.getName());
            options.add(option);
        }
        return new BaseResponseVO<>(options);
    }
}
