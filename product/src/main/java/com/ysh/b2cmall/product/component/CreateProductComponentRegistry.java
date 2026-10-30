package com.ysh.b2cmall.product.component;

import com.ysh.b2cmall.common.exception.BizException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 商品创建组件注册表。
 * 收集所有 {@link CreateProductTemplate} 子类，按 {@link ProductType} 建立映射，
 * 把 Controller 里的 if-else 类型分发收敛为按类型查表，新增商品类型只需加组件无需改分发逻辑。
 */
@Component
public class CreateProductComponentRegistry {

    private final Map<ProductType, CreateProductTemplate> registry = new EnumMap<>(ProductType.class);

    public CreateProductComponentRegistry(List<CreateProductTemplate> templates) {
        for (CreateProductTemplate template : templates) {
            CreateProductTemplate previous = registry.put(template.supportType(), template);
            if (previous != null) {
                throw new IllegalStateException("商品类型 " + template.supportType()
                        + " 存在多个创建组件：" + previous.getClass().getSimpleName()
                        + " 与 " + template.getClass().getSimpleName());
            }
        }
    }

    /** 按商品类型返回对应创建组件，未注册类型明确报 400 */
    public CreateProductTemplate get(Integer type) {
        ProductType productType = ProductType.from(type);
        CreateProductTemplate template = registry.get(productType);
        if (template == null) {
            throw BizException.badRequest("商品类型暂不支持创建：" + productType.name());
        }
        return template;
    }
}
