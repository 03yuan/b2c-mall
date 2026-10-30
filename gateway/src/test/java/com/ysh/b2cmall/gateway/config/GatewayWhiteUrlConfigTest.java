package com.ysh.b2cmall.gateway.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.AntPathMatcher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 网关白名单配置契约测试（TDD 红阶段）。
 * 对应 .tdd/task/mall-category-product-list.md 功能上下文 2.1.1（gateway 白名单）：
 * - 2.1.1 application.yml 的 b2c.auth.white-urls 新增 /product/mall/**（Ant 风格匹配 mall 下所有路径）
 *   （Fail 边界：漏配 -> 网关 AuthFilter 拦截，全线 401；配成 /product/mall 不带 /** -> 子路径不被匹配）
 *
 * 用例分类（表格驱动，适配白名单配置契约）：
 * 1. 字段级校验：white-urls 必须包含 /product/mall/** 条目
 * 2. 类型与格式：该条目为 Ant 风格（带 /**），能匹配 mall 下所有子路径（非仅单层）
 * 3. 重复创建：重复加载配置结果一致（幂等）
 * 4. 非法输入：不完整 pattern（/product/mall 无 /**）不能匹配子路径，防止漏配
 *
 * 直接读取 application.yml 文件，抽取 b2c.auth.white-urls 段落的路径条目做契约断言，
 * 不启动完整 Spring 容器（离线环境下依赖树最小化），聚焦「白名单配置是否真实含
 * /product/mall/** 且能覆盖 mall 所有子路径」。
 */
class GatewayWhiteUrlConfigTest {

    private static final String MALL_PATTERN = "/product/mall/**";

    /** 读取 application.yml 中 white-urls 段落的路径条目（缩进 "- " 开头） */
    private List<String> whiteUrls() throws Exception {
        ClassPathResource resource = new ClassPathResource("application.yml");
        List<String> lines;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            lines = reader.lines().collect(Collectors.toList());
        }

        List<String> urls = new ArrayList<>();
        boolean inWhiteUrls = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.equals("white-urls:")) {
                inWhiteUrls = true;
                continue;
            }
            if (inWhiteUrls) {
                if (trimmed.startsWith("- ")) {
                    urls.add(trimmed.substring(2).trim());
                } else if (!trimmed.isEmpty() && !line.startsWith(" ") && !line.startsWith("\t")) {
                    // 遇到下一个非缩进顶层键，白名单段结束
                    break;
                }
            }
        }
        return urls;
    }

    // ============ 1. 字段级校验：white-urls 含 /product/mall/** ============

    @Test
    void 白名单含productMall通配条目() throws Exception {
        List<String> urls = whiteUrls();
        assertTrue(urls.contains(MALL_PATTERN),
                "white-urls 必须包含 " + MALL_PATTERN + "（漏配则 C 端 mall 接口全线 401），实际：" + urls);
    }

    // ============ 2. 类型与格式：/** 能匹配 mall 下所有子路径（表格驱动） ============

    static Stream<Arguments> mallSubPaths() {
        return Stream.of(
                arguments("/product/mall/categories"),
                arguments("/product/mall/products"),
                arguments("/product/mall/products/1"),
                arguments("/product/mall/foo/bar/baz")
        );
    }

    @ParameterizedTest(name = "匹配 {0}")
    @MethodSource("mallSubPaths")
    void 通配条目匹配mall所有子路径(String path) {
        AntPathMatcher matcher = new AntPathMatcher();
        assertTrue(matcher.match(MALL_PATTERN, path),
                MALL_PATTERN + " 必须匹配 " + path + "（配成 /product/mall 不带 /** 会漏配子路径）");
    }

    // ============ 4. 非法输入：不完整 pattern 不匹配子路径（防漏配） ============

    @Test
    void 不带通配的pattern不能匹配子路径_防漏配() {
        AntPathMatcher matcher = new AntPathMatcher();
        assertTrue(!matcher.match("/product/mall", "/product/mall/categories"),
                "只配 /product/mall（不带 /**）无法覆盖子路径，必须用 /** 写法");
    }

    // ============ 3. 重复创建：重复加载配置结果一致 ============

    @Test
    void 重复加载白名单配置结果一致() throws Exception {
        List<String> first = whiteUrls();
        List<String> second = whiteUrls();

        assertTrue(first.containsAll(second) && second.containsAll(first) && first.size() == second.size(),
                "重复加载白名单配置结果必须一致");
        assertTrue(first.contains(MALL_PATTERN), "重复加载后仍应含 " + MALL_PATTERN);
    }
}
