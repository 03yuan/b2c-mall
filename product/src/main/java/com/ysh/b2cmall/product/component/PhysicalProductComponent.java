package com.ysh.b2cmall.product.component;

import cn.hutool.core.lang.Assert;
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
    protected void validatePriceAndStock(CreateProductRequest req) {
        Assert.notNull(req.getStock(), "实物商品库存不能为空");
        Assert.isTrue(req.getStock() >= 0, "实物商品库存不能为负数");
    }

    @Override
    protected void auditContent(CreateProductRequest req) {
        String text = (req.getName() == null ? "" : req.getName())
                + (req.getDescription() == null ? "" : req.getDescription());
        for (String word : FORBIDDEN_WORDS) {
            Assert.isFalse(text.contains(word), "商品内容包含违禁词：" + word);
        }
    }
}
