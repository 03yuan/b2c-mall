package com.ysh.b2cmall.product.web.controller;

import com.ysh.b2cmall.common.response.BaseResponseVO;
import com.ysh.b2cmall.product.dao.po.MallSkuQuery;
import com.ysh.b2cmall.product.dao.po.MallSortType;
import com.ysh.b2cmall.product.dao.po.SkuPO;
import com.ysh.b2cmall.product.dao.po.SkuPageResult;
import com.ysh.b2cmall.product.service.CategoryService;
import com.ysh.b2cmall.product.service.SkuService;
import com.ysh.b2cmall.product.web.vo.MallProductItemVO;
import com.ysh.b2cmall.product.web.vo.MallProductListVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * C 端商城公开接口（免登录，无 RequestHolderComponent 依赖）。
 * 对应 .tdd/task/mall-category-product-list.md 功能点 1/2/3/4/5。
 */
@RestController
@RequestMapping("/product/mall")
public class MallProductController {

    private final CategoryService categoryService;
    private final SkuService skuService;

    public MallProductController() {
        this(null, null);
    }

    @Autowired
    public MallProductController(CategoryService categoryService, SkuService skuService) {
        this.categoryService = categoryService;
        this.skuService = skuService;
    }

    /** C 端分类导航：全平台一级分类名（去重，公开免登录） */
    @GetMapping("/categories")
    public BaseResponseVO<List<String>> categories() {
        return new BaseResponseVO<>(categoryService.listMallTopLevelNames());
    }

    /** C 端商品卡片列表：公开免登录，categoryName/sortBy 可选，page/size 校验 */
    @GetMapping("/products")
    public BaseResponseVO<MallProductListVO> products(
            @RequestParam(required = false) String categoryName,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        MallSkuQuery query = new MallSkuQuery();
        query.setCategoryName(categoryName);
        if (sortBy != null) {
            query.setSortBy(parseSortBy(sortBy));
        }
        if (page != null) {
            query.setPage(page);
        }
        if (size != null) {
            query.setSize(size);
        }

        SkuPageResult result = skuService.queryMallPage(query);

        List<MallProductItemVO> items = result.getRows().stream()
                .map(this::toItem)
                .collect(Collectors.toList());

        MallProductListVO vo = new MallProductListVO();
        vo.setList(items);
        vo.setTotal(result.getTotal());
        return new BaseResponseVO<>(vo);
    }

    /** SKU -> 卡片 VO：只保留 skuId/thumbnail/name/price/displaySales 五要素（字段白名单） */
    private MallProductItemVO toItem(SkuPO po) {
        MallProductItemVO item = new MallProductItemVO();
        item.setSkuId(po.getId());
        item.setThumbnail(firstImage(po.getImages()));
        item.setName(po.getName());
        item.setPrice(po.getPrice());
        item.setDisplaySales(po.getDisplaySales());
        return item;
    }

    /** images 逗号分隔取首图；无图返回 null（不伪造占位图） */
    private String firstImage(String images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        int comma = images.indexOf(',');
        return comma < 0 ? images : images.substring(0, comma);
    }

    /** sortBy 字符串 -> 枚举白名单（严格大写匹配）；非法值拒绝而非静默降级 */
    private MallSortType parseSortBy(String sortBy) {
        try {
            return MallSortType.valueOf(sortBy);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("排序方式不合法");
        }
    }
}
