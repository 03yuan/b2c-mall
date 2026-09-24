package com.ysh.b2cmall.product.web.controller;

import com.ysh.b2cmall.common.response.BaseResponseVO;
import com.ysh.b2cmall.product.component.CreateProductTemplate;
import com.ysh.b2cmall.product.component.PhysicalProductComponent;
import com.ysh.b2cmall.product.component.VirtualProductComponent;
import com.ysh.b2cmall.product.web.request.CreateProductRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/product")
public class ProductController {

    @Autowired
    private PhysicalProductComponent physicalProductComponent;

    @Autowired
    private VirtualProductComponent virtualProductComponent;

    /**
     * 新建商品
     * 根据 type 分发到实物/虚拟商品模板
     */
    @PostMapping("/create")
    public BaseResponseVO<Map<String, Object>> create(@RequestBody @Validated CreateProductRequest req) {
        CreateProductTemplate template;
        if (Integer.valueOf(2).equals(req.getType())) {
            template = virtualProductComponent;
        } else {
            template = physicalProductComponent;
        }
        Integer skuId = template.create(req);

        Map<String, Object> data = new HashMap<>();
        data.put("skuId", skuId);
        return new BaseResponseVO<>(data);
    }
}
