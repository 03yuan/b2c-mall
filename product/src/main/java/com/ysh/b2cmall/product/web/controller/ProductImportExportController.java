package com.ysh.b2cmall.product.web.controller;

import com.alibaba.excel.EasyExcel;
import cn.hutool.core.lang.Assert;
import com.ysh.b2cmall.common.response.BaseResponseVO;
import com.ysh.b2cmall.product.component.RequestHolderComponent;
import com.ysh.b2cmall.product.dao.po.SkuPO;
import com.ysh.b2cmall.product.dao.po.SkuQuery;
import com.ysh.b2cmall.product.service.ImportResult;
import com.ysh.b2cmall.product.service.SkuService;
import com.ysh.b2cmall.product.web.excel.ProductExcelColumns;
import com.ysh.b2cmall.product.web.vo.ImportResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 商品导入导出入口（功能上下文 5.x/6.x）
 */
@RestController
@RequestMapping("/product")
public class ProductImportExportController {

    /** shopId 恒取登录上下文（2.2），导出复用列表筛选（5.1） */
    private final SkuService skuService;

    /** 登录上下文来源 */
    private final RequestHolderComponent requestHolder;

    public ProductImportExportController() {
        this(null, null);
    }

    @Autowired
    public ProductImportExportController(SkuService skuService, RequestHolderComponent requestHolder) {
        this.skuService = skuService;
        this.requestHolder = requestHolder;
    }

    /** 导出商品列表 Excel（与列表共用同一筛选，取全量不分页；表头=导入模板单一列定义） */
    @GetMapping("/export")
    public void exportExcel(@RequestParam(required = false) String name,
                            @RequestParam(required = false) Integer productType,
                            @RequestParam(required = false) Integer type,
                            @RequestParam(required = false) Integer status,
                            @RequestParam(required = false) Integer categoryId,
                            @RequestParam(required = false) List<String> deliveryMethods,
                            HttpServletResponse response) throws IOException {
        // shopId 恒取登录上下文，请求参数中的 shopId 一律忽略；筛选与列表同源（5.1）
        SkuQuery query = new SkuQuery();
        query.setShopId(requestHolder.get().getShopId());
        query.setName(name);
        query.setProductType(productType);
        query.setType(type);
        query.setStatus(status);
        query.setCategoryId(categoryId);
        query.setDeliveryMethods(deliveryMethods);
        List<SkuPO> rows = skuService.exportList(query);

        String fileName = "商品列表_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8.name()));

        List<List<String>> head = ProductExcelColumns.HEADERS.stream()
                .map(Collections::singletonList)
                .collect(Collectors.toList());
        List<List<String>> data = rows.stream()
                .map(ProductExcelColumns::toRow)
                .collect(Collectors.toList());
        EasyExcel.write(response.getOutputStream()).head(head).sheet("商品列表").doWrite(data);
    }

    /** 上传大小上限 5MB（功能上下文 6.2.1） */
    private static final long MAX_IMPORT_FILE_SIZE = 5L * 1024 * 1024;

    /** 导入商品 Excel（批量新增/更新，按商品ID 分流；文件校验先行，不合法不解析） */
    @PostMapping("/import")
    public BaseResponseVO<ImportResultVO> importExcel(@RequestParam("file") MultipartFile file) throws IOException {
        // 文件校验（6.2.1）：非 .xlsx / 空文件 / 超大小上限 -> 400
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        Assert.isTrue(filename.toLowerCase().endsWith(".xlsx"), "仅支持 .xlsx 格式的 Excel 文件");
        Assert.isTrue(!file.isEmpty(), "上传文件为空");
        Assert.isTrue(file.getSize() <= MAX_IMPORT_FILE_SIZE, "文件大小不能超过 5MB");

        // shopId 恒取登录上下文，请求参数中的 shopId 一律忽略
        ImportResult result = skuService.importExcel(requestHolder.get().getShopId(), file.getInputStream());
        return new BaseResponseVO<>(
                new ImportResultVO(result.getInserted(), result.getUpdated(), result.getErrors()));
    }
}
