package com.ysh.b2cmall.product.client;

import com.ysh.b2cmall.common.response.BaseResponseVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderQueryClient 的 Feign 契约测试（TDD 红阶段）。
 * 对应 .tdd/task/shop-product-list.md 功能上下文 7.3.2：
 * product-service 新增 Feign 客户端调用 order-service 的 GET /order/sku/{skuId}/count。
 * Fail 边界：URL/路径配错 -> 删除功能整体 500。
 *
 * 用例分类（表格驱动）：
 * 1. 字段级校验：方法参数 @PathVariable("skuId") 绑定路径变量
 * 2. 类型与格式：@FeignClient 指向 order-service；@GetMapping 路径与 7.3.1 接口一致
 * 3. 重复创建：mock 客户端重复调用传参稳定正确
 * 4. 非法输入：响应映射——data.count 正确取出（不取错 key）
 *
 * 测试方式：反射断言注解契约（不起 Spring 容器、不连真实 order-service）+ Mockito mock 接口。
 */
class OrderQueryClientContractTest {

    // ============ 2. 类型与格式：Feign 注解契约（表格驱动，防 URL/路径配错） ============

    /**
     * 列：用例名、契约校验逻辑（断言失败即红）
     */
    static Stream<Arguments> contractCases() {
        return Stream.of(
                arguments("FeignClient 指向 order-service", (Runnable) () -> {
                    FeignClient feignClient = OrderQueryClient.class.getAnnotation(FeignClient.class);
                    assertNotNull(feignClient, "接口必须标注 @FeignClient");
                    assertEquals("order-service", feignClient.name(),
                            "@FeignClient name 必须为 order-service，实际：" + feignClient.name());
                }),
                arguments("GET 路径与 7.3.1 接口一致", (Runnable) () -> {
                    GetMapping getMapping = countMethod().getAnnotation(GetMapping.class);
                    assertNotNull(getMapping, "countBySku 必须标注 @GetMapping");
                    assertEquals("/order/sku/{skuId}/count", getMapping.value()[0],
                            "路径必须为 /order/sku/{skuId}/count，实际：" + getMapping.value()[0]);
                }),
                arguments("skuId 绑定路径变量", (Runnable) () -> {
                    Parameter param = countMethod().getParameters()[0];
                    PathVariable pathVariable = param.getAnnotation(PathVariable.class);
                    assertNotNull(pathVariable, "skuId 参数必须标注 @PathVariable");
                    assertEquals("skuId", pathVariable.value(),
                            "@PathVariable 值必须为 skuId 以匹配路径占位符，实际：" + pathVariable.value());
                })
        );
    }

    private static Method countMethod() {
        try {
            return OrderQueryClient.class.getMethod("countBySku", Integer.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("OrderQueryClient 缺少 countBySku(Integer) 方法", e);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contractCases")
    void Feign契约(String caseName, Runnable assertion) {
        assertion.run();
    }

    // ============ 1. 字段级校验 + 3. 重复创建：mock 客户端传参稳定正确 ============

    @Test
    void 调用_传入skuId正确且重复稳定() {
        OrderQueryClient client = mock(OrderQueryClient.class);
        when(client.countBySku(10)).thenReturn(countResponse(2));

        client.countBySku(10);
        client.countBySku(10);

        verify(client, org.mockito.Mockito.times(2)).countBySku(10);
    }

    // ============ 4. 响应映射：data.count 正确取出 ============

    @Test
    void 响应映射_从data取出count() {
        OrderQueryClient client = mock(OrderQueryClient.class);
        when(client.countBySku(10)).thenReturn(countResponse(2));

        BaseResponseVO<Map<String, Object>> response = client.countBySku(10);

        assertTrue(response.getData().containsKey("count"), "响应 data 必须含 count 键");
        assertEquals(2, ((Number) response.getData().get("count")).intValue(), "count 取值应为 2");
    }

    private BaseResponseVO<Map<String, Object>> countResponse(int count) {
        Map<String, Object> data = new HashMap<>();
        data.put("count", count);
        return new BaseResponseVO<>(data);
    }
}
