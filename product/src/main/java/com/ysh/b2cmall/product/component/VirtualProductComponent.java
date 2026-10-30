package com.ysh.b2cmall.product.component;

import com.ysh.b2cmall.common.exception.BizException;
import com.ysh.b2cmall.product.web.request.CreateProductRequest;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 虚拟商品创建组件。
 * 差异化：
 *  - 价格库存校验：库存可为空（无限库存，如卡密/会员自动发货）；有值时 >= 0
 *  - 内容合规审核：严格合规（描述不能为空 + 违禁词 + 资质敏感词）
 */
@Component
public class VirtualProductComponent extends CreateProductTemplate {

    private static final List<String> FORBIDDEN_WORDS = Arrays.asList("违禁", "假货", "高仿", "破解", "外挂");

    private static final List<String> QUALIFICATION_KEYWORDS = Arrays.asList("充值", "话费", "会员", "卡密");

    @Override
    protected ProductType supportType() {
        return ProductType.VIRTUAL;
    }

    @Override
    protected void validatePriceAndStock(CreateProductRequest req) {
        // 虚拟商品库存可为空（无限库存）
        if (req.getStock() != null && req.getStock() < 0) {
            throw BizException.badRequest("库存不能为负数");
        }
    }

    @Override
    protected void auditContent(CreateProductRequest req) {
        // 虚拟商品描述必填（需说明交付方式）
        if (req.getDescription() == null || req.getDescription().trim().isEmpty()) {
            throw BizException.badRequest("虚拟商品描述不能为空（需说明交付方式）");
        }

        String text = req.getName() + req.getDescription();
        for (String word : FORBIDDEN_WORDS) {
            if (text.contains(word)) {
                throw BizException.badRequest("商品内容包含违禁词：" + word);
            }
        }

        // 涉及资质类目的虚拟商品，名称中需包含交付说明关键词（简化校验）
        boolean needQualification = QUALIFICATION_KEYWORDS.stream().anyMatch(text::contains);
        if (needQualification && !(text.contains("自动") || text.contains("发货") || text.contains("秒发"))) {
            throw BizException.badRequest("资质类虚拟商品需在描述中说明交付方式（如自动发货/秒发）");
        }
    }
}
