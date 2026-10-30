package com.ysh.b2cmall.product.component;

import com.ysh.b2cmall.common.exception.BizException;
import com.ysh.b2cmall.product.web.request.CreateProductRequest;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 实物商品创建组件。
 * 差异化：
 *  - 价格库存校验：库存必须 >= 0（实物需物流配送，必须明确库存）
 *  - 内容合规审核：基础违禁词校验
 */
@Component
public class PhysicalProductComponent extends CreateProductTemplate {

    private static final List<String> FORBIDDEN_WORDS = Arrays.asList("违禁", "假货", "高仿");

    @Override
    protected ProductType supportType() {
        return ProductType.PHYSICAL;
    }

    @Override
    protected void validatePriceAndStock(CreateProductRequest req) {
        if (req.getStock() == null) {
            throw BizException.badRequest("实物商品库存不能为空");
        }
        if (req.getStock() < 0) {
            throw BizException.badRequest("实物商品库存不能为负数");
        }
    }

    @Override
    protected void auditContent(CreateProductRequest req) {
        String text = (req.getName() == null ? "" : req.getName())
                + (req.getDescription() == null ? "" : req.getDescription());
        for (String word : FORBIDDEN_WORDS) {
            if (text.contains(word)) {
                throw BizException.badRequest("商品内容包含违禁词：" + word);
            }
        }
    }
}
