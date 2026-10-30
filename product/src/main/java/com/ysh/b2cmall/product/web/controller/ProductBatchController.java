package com.ysh.b2cmall.product.web.controller;

import com.ysh.b2cmall.common.response.BaseResponseVO;
import com.ysh.b2cmall.product.component.RequestHolderComponent;
import com.ysh.b2cmall.product.service.BatchUpdateResult;
import com.ysh.b2cmall.product.service.SkuService;
import com.ysh.b2cmall.product.web.request.BatchUpdateRequest;
import com.ysh.b2cmall.product.web.vo.BatchUpdateVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品批量设置入口（功能上下文 4.x）
 */
@RestController
@RequestMapping("/product")
public class ProductBatchController {

    /** shopId 恒取登录上下文（2.2），批量更新委托 Service（4.1） */
    private final SkuService skuService;

    /** 登录上下文来源 */
    private final RequestHolderComponent requestHolder;

    public ProductBatchController() {
        this(null, null);
    }

    @Autowired
    public ProductBatchController(SkuService skuService, RequestHolderComponent requestHolder) {
        this.skuService = skuService;
        this.requestHolder = requestHolder;
    }

    /** 批量设置（状态/配送方式/分类/排序/库存），响应含生效条数与跳过条数 */
    @PostMapping("/batch")
    public BaseResponseVO<BatchUpdateVO> batch(@RequestBody @Validated BatchUpdateRequest request) {
        // shopId 恒取登录上下文，请求参数中的 shopId 一律忽略
        BatchUpdateResult result = skuService.batchUpdate(requestHolder.get().getShopId(),
                request.getSkuIds(), request.getAction(), request.getValue());
        return new BaseResponseVO<>(new BatchUpdateVO(result.getAffected(), result.getSkipped()));
    }
}
