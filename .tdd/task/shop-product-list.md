# 店铺商品管理（商品列表工作台）TDD 拆解文档

## 需求概述

店铺全部商品的统一维护工作台，面向商家/店铺运营人员，登录商家账号才可访问、只看当前登录店铺的数据。核心内容：①顶部 6 组筛选（商品名称模糊、商品类型、商品类别、状态、配送方式多选、商品分类）+ 搜索/重置；②商品分页列表（缩略图+名称、市场价、销售价、真实销量、注水销量、仓库库存、门店库存、商品类型、商品类别、状态、配送方式、排序）；③全选复选框 + 批量设置（状态/配送方式/分类/排序/库存）；④导出 Excel、导入 Excel（批量新增/更新）；⑤单品操作（编辑、上架/下架、删除，已产生订单禁止删除）。来源：`shop_product_list.docx`（项目需求 v1.1）。

**架构背景**：Maven 多模块微服务（Gateway 8081 → product-service）。商品数据归 product-service 私有库 product.db（`product.sql` 建表，SQLite，schema 变更须先 DROP 旧表重建）；订单数据归 order-service（order.db），严禁跨库互查。分层统一：`web`（Controller + Request/VO + GlobalExceptionHandler）→ `service` → `dao/mapper` + `dao/po`。登录态：TokenFilter 从 Authorization Bearer token 解析 Redis `b2c:token:{token}` 得 employeeId/shopId/username 写入 `RequestHolderComponent`（`CurrentEmployeeBean` 含 shopId）。现有商品能力仅 `POST /product/create`（CreateProductTemplate 模板方法按 type 分发实物/虚拟）与 `saveSku/putOnSale`，列表/批量/导入导出/删除均为新增。order-service 已在 Dashboard 需求中通过 RocketMQ topic `b2c-order-events`（tag `PAY_SUCCESS`）发布支付成功事件，本需求可复用。Excel 处理引入 EasyExcel（当前 pom 无任何 Excel 依赖）。

**已确认的决策**（2026-10-23 交互确认）：
- 「商品类型」与「商品类别」是两个独立字段：tb_sku 现有 `type` 语义扩展为**商品类别**（1-实物 2-虚拟 3-卡券，商品创建模板方法仍按它分发），**新增** `product_type` 存**商品类型**（1-普通商品 2-秒杀商品）
- 「商品销量：真实成交销量」由 MQ 事件回写：product-service 消费 order-service 的 `PAY_SUCCESS` 事件，按 skuId 累加 `sales`（复用已有 topic）
- 导入 Excel 按**商品ID**识别新增/更新：ID 为空→新增；ID 存在且属本店→更新；ID 不存在或属他店→该行报错（带行号），其余行照常处理（逐行独立，不整批回滚）
- 批量设置库存语义为「**设为固定值**」（非增减），排序同理统一设为 N
- 市场价为划线原价（列表展示用），销售价沿用现有 `price`；注水销量商家可配置（非负）
- 空数据一律「暂无数据」语义：列表返回空集合 + total=0，不显示伪造数字（沿用 Dashboard 约定）
- 删除的「二次确认弹窗」是前端职责，后端只保证「已产生订单禁止删除」的校验

**字段映射**（需求列 → tb_sku 字段）：

| 需求列 | 字段 | 现状 |
|---|---|---|
| 商品信息（缩略图+名称） | `images`（取首图）+ `name` | 已有 |
| 市场价 | `market_price` | **新增** |
| 销售价 | `price` | 已有（改注释） |
| 商品销量 | `sales` | **新增**（MQ 累加） |
| 注水销量 | `fake_sales` | **新增**（商家配置） |
| 仓库库存 | `stock` | 已有（改注释） |
| 门店库存 | `store_stock` | **新增** |
| 商品类型（普通/秒杀） | `product_type` | **新增** |
| 商品类别（实物/虚拟/卡券） | `type`（1实物 2虚拟 3卡券） | 已有扩展 |
| 状态（上架/下架） | `status` | 已有 |
| 配送方式（多选） | `delivery_methods`（逗号分隔：CITY/PICKUP/EXPRESS） | **新增** |
| 排序（越大越靠前） | `sort` | **新增** |

---

## 功能点 1：tb_sku 数据模型扩展（schema 变更先行）

### 功能上下文 1.1：DAO/PO 层 - 表结构与实体

- 任务项 1.1.1：`product.sql` 的 tb_sku 新增字段 market_price、sales、fake_sales、store_stock、product_type、delivery_methods、sort；type 注释改为商品类别并支持 3-卡券；stock 注释明确为仓库库存；schema 变更先 `DROP TABLE IF EXISTS tb_sku` 再重建（SQLite CREATE IF NOT EXISTS 不改表）
  - 测试思路：真实 SQLite 临时库执行 product.sql → 插入一条含全部新字段的 sku → 逐字段读回断言一致。Fail 边界：漏字段 → `no such column`；忘 DROP 旧表 → 新字段静默丢失

- 任务项 1.1.2：SkuPO 同步新增对应属性（marketPrice、sales、fakeSales、storeStock、productType、deliveryMethods、sort）
  - 测试思路：insert 后按 id 查询断言 PO 各属性非 null 且值正确。Fail 边界：PO 漏属性导致 MyBatis 映射丢列

- 任务项 1.1.3：默认值约束：sales=0、fake_sales=0、sort=0、store_stock=0（未显式传入时）
  - 测试思路：插入不带销量/排序的 sku → 读回断言为 0 而非 null。Fail 边界：默认值缺失 → 列表显示 null 与「暂无数据」混淆

## 功能点 2：登录商家只看到自己店铺的商品（权限与隔离）

### 功能上下文 2.1：Filter/组件层 - 登录态注入（复用现有 TokenFilter + RequestHolderComponent）

- 任务项 2.1.1：所有新增商品接口（list/import/export/batch/detail/update/status/delete）不在鉴权白名单，无有效 token 返回 401
  - 测试思路：不带 Authorization 头请求各接口 → 断言 401。Fail 边界：误加白名单 → 未登录可看/改商品

- 任务项 2.1.2：TokenFilter 解析 token 后将 shopId 写入 RequestHolderComponent，业务代码只从登录上下文取 shopId
  - 测试思路：带 token 请求 → 断言 RequestHolderComponent.get().getShopId() 与 Redis 中一致。Fail 边界：shopId 为 null 时查询条件失效（查出全店数据）

### 功能上下文 2.2：Service/DAO 层 - shopId 强制隔离

- 任务项 2.2.1：所有查询/更新/删除 SQL 恒带 `shop_id = 登录 shopId`，请求参数中的 shopId 一律忽略
  - 测试思路：MockMvc 带 `?shopId=2` 调用 → Mockito `verify(only())` 断言 Service 仅以登录 shopId 被调用（沿用 Dashboard 测试模式）。Fail 边界：信任前端 shopId → 水平越权

- 任务项 2.2.2：跨店 id 防护：批量/编辑/下架/删除传入他店 skuId → 不生效（该行报错或忽略），他店数据零改动
  - 测试思路：店铺 A 登录传店铺 B 的 skuId 下架 → 断言 B 商品 status 不变。Fail 边界：UPDATE/DELETE 漏 shopId 条件 → 改到他店数据

## 功能点 3：商品列表分页展示 + 多维度筛选（搜索/重置）

### 功能上下文 3.1：DAO 层（SkuMapper + XML 动态 SQL）

- 任务项 3.1.1：动态条件分页查询：shopId 固定；name LIKE 模糊（%%）；product_type、type、status、category_id 等值匹配；delivery_methods 多选（命中任一所选即返回）
  - 测试思路：插入覆盖各维度的 sku → 断言单个筛选条件各自命中正确子集；勾选「同城配送+快递」→ 断言含其一的商品都返回。Fail 边界：LIKE 不带 % 变精确匹配；多选写成 AND（必须同时支持全部配送方式）→ 结果缺失

- 任务项 3.1.2：排序规则：sort DESC（数字越大前台越靠前），同 sort 按 id DESC 稳定排序
  - 测试思路：插入 sort=3/1/2 三条 → 断言返回顺序 3→2→1；两条同 sort → 断言分页翻页不重复不漏行。Fail 边界：默认按 id 排 → 排序字段失效

- 任务项 3.1.3：分页边界：page≥1、size 限 1~100；超页返回空列表；total=筛选命中总数
  - 测试思路：10 条数据 size=3 → 断言 total=10、每页 3 条、第 4 页为空。Fail 边界：size=0/负数 → SQL 异常 500；total 不随筛选变化

### 功能上下文 3.2：Service/Controller 层（GET /product/list）

- 任务项 3.2.1：Controller 参数校验（非法 page/size → 400）+ VO 组装：每行含缩略图（images 第一张图，非整串）、商品名称、市场价、销售价、商品销量、注水销量、仓库库存、门店库存、商品类型、商品类别、状态、配送方式、排序、skuId
  - 测试思路：MockMvc standalone 携带登录上下文 → 断言 200 且 VO 各列齐全，缩略图为 images 首个 URL。Fail 边界：漏列（门店库存/注水销量）前端表头对不上；images 整串返回

- 任务项 3.2.2：空结果返回空列表 + total=0（前端渲染「暂无数据」），任何字段不伪造数值
  - 测试思路：无匹配数据 → 断言 list 为空集合、total=0。Fail 边界：填充 0/示例数据（用户明确反感虚假数据）

### 功能上下文 3.3：Service/DAO 层 - 商品分类下拉数据

- 任务项 3.3.1：GET /product/categories 返回当前登录店铺、status=1 的类目列表（id+name，按 sort 排序），供筛选与批量设置的「商品分类」下拉
  - 测试思路：两个店铺各插 2 个类目（含 1 个禁用）→ 断言只返回本店启用类目。Fail 边界：返回他店/禁用类目 → 筛选出他店商品

## 功能点 4：批量设置（全选 + 批量改状态/配送方式/分类/排序/库存）

### 功能上下文 4.1：Service 层 - 批量更新（按操作类型分发）

- 任务项 4.1.1：批量改状态：勾选 ids 统一设为上架/下架
  - 测试思路：3 个商品批量下架 → 断言 3 条 status=0；再批量上架 → 全部 status=1。Fail 边界：只支持单向（只能下架不能上架）

- 任务项 4.1.2：批量改配送方式/分类/排序/库存：配送方式整组替换、分类改 category_id、排序统一设为 N、库存**统一设为固定值**（非增减）
  - 测试思路：表驱动 5 种操作各断言更新结果；库存原值 7 批量设为 5 → 断言为 5（Fail 边界：做成 7+5=12 的增减语义）；分类需校验 categoryId 属于本店且存在

- 任务项 4.1.3：入参校验 → 400：ids 为空、操作类型非法、库存/门店库存为负、排序非整数、分类不存在
  - 测试思路：表驱动非法输入各断言 400 且中文错误消息，DB 零改动。Fail 边界：负库存入库

- 任务项 4.1.4：批量更新恒带 shopId，ids 中混入他店 skuId → 该 id 不被更新（计入跳过数）
  - 测试思路：登录店铺 A，ids 含 A、B 两店商品 → 断言仅 A 商品变更、B 商品原样。Fail 边界：UPDATE ... WHERE id IN (?) 漏 shopId

### 功能上下文 4.2：Controller 层（POST /product/batch）

- 任务项 4.2.1：请求体（skuIds + action + value）校验后调 Service，响应含生效条数与跳过条数
  - 测试思路：MockMvc 断言 200 且 affected=2、skipped=1（混入他店 id）。Fail 边界：无反馈条数前端无法提示部分失败

## 功能点 5：导出商品列表 Excel

### 功能上下文 5.1：Service 层 - 导出数据组装（复用列表筛选）

- 任务项 5.1.1：导出与列表共用同一筛选构建器：按当前筛选条件取全量（不分页）数据行
  - 测试思路：带筛选条件导出 → 断言行数=该条件命中总数、且不含条件外商品。Fail 边界：忽略筛选导出全店数据

### 功能上下文 5.2：web 层 - Excel 下载（EasyExcel）

- 任务项 5.2.1：GET /product/export 返回 .xlsx 下载流，表头列=列表列+商品ID（即导入模板，单一列定义来源），文件名含时间戳，响应头 Content-Disposition 正确
  - 测试思路：MockMvc 断言响应 Content-Type 为 xlsx、文件名非空；读回 Excel 断言首行表头与模板列定义逐列一致。Fail 边界：导出表头与导入模板各自硬编码 → 漂移后导出文件无法导入

## 功能点 6：导入商品 Excel（批量新增/更新，按商品ID）

### 功能上下文 6.1：Service 层 - 解析、校验、按 ID 分流

- 任务项 6.1.1：解析 .xlsx 行为商品数据，逐行字段校验：必填（名称、销售价、商品类型、商品类别、状态）、价格为非负数字、库存/销量/排序为非负整数、类型/类别/状态/配送方式取值在枚举内，错误行收集「行号+原因」
  - 测试思路：构造含非法行（负库存/枚举值写错/缺名称）的文件 → 断言错误列表逐行含行号与原因。Fail 边界：一行错 → 整个文件 500 无定位信息

- 任务项 6.1.2：按商品ID分流：ID 空 → 新增；ID 存在且属本店 → 更新；ID 不存在或属他店 → 该行报错；逐行独立处理，好行照常入库
  - 测试思路：3 行（ID 空 / 本店 ID / 他店 ID）→ 断言新增 1 条、更新 1 条、错误 1 条，DB 实际变化正确。Fail 边界：他店 ID 被更新 → 越权写入；全文件被错误行连坐回滚

- 任务项 6.1.3：新增/更新时销量字段保护：sales 以 DB/事件为准不被 Excel 覆盖（导入可改注水销量，不改真实销量）
  - 测试思路：导入行带 sales=999 → 断言 DB 中 sales 保持原值、fake_sales 更新。Fail 边界：真实销量被商家随意改写 → 数据失真

### 功能上下文 6.2：Controller 层（POST /product/import，multipart）

- 任务项 6.2.1：上传文件校验：非 .xlsx、空文件、超大小上限 → 400
  - 测试思路：上传 .txt / 0 字节文件 → 断言 400 且消息明确。Fail 边界：500 或静默成功

- 任务项 6.2.2：响应汇总：inserted 条数、updated 条数、errors（行号+原因）列表
  - 测试思路：断言响应结构含 inserted/updated/errors 三段且与实际入库一致。Fail 边界：无汇总 → 前端无法提示用户哪行失败

## 功能点 7：单品操作（编辑、上架/下架、删除）

### 功能上下文 7.1：Controller + Service - 编辑（详情查询 + 更新）

- 任务项 7.1.1：GET /product/detail/{skuId} 返回编辑页全部字段（含 images 图文、spec、配送方式、双库存、双销量、排序），shopId 隔离，他店/不存在 → 404
  - 测试思路：断言返回 VO 字段齐全；他店 skuId → 404。Fail 边界：漏字段编辑页显示残缺

- 任务项 7.1.2：PUT /product/update 更新基础信息/价格/库存/图文/配送/排序/类型/类别/注水销量，字段校验同导入；不改 sales
  - 测试思路：更新后读回断言一致；非法值 → 400；他店 skuId → 404 且零改动。Fail 边界：更新语句覆盖 shopId（商品被"搬店"）

### 功能上下文 7.2：Service - 上架/下架切换

- 任务项 7.2.1：POST /product/status 将勾选商品设为目标状态（上架商品显示【下架】→ 设 0；下架商品显示【上架】→ 设 1），shopId 隔离
  - 测试思路：1→0、0→1 双向各断言 DB status；重复对同状态操作幂等成功。Fail 边界：只实现下架未实现上架

### 功能上下文 7.3：删除（跨服务校验：order-service + product-service）

- 任务项 7.3.1：order-service 新增订单存在性统计接口：GET /order/sku/{skuId}/count 返回该商品的订单条数（shopId 隔离，任意状态的订单都算「已产生订单」）
  - 测试思路：真实 order 库插入该 skuId 的订单 2 条 + 他店同 skuId 计 0 条（skuId 全局唯一则插他店其他商品）→ 断言计数 2；无订单 → 0。Fail 边界：只统计已支付订单 → 有未支付单的商品被误删

- 任务项 7.3.2：product-service 新增 Feign 客户端 OrderQueryClient 调用上述接口
  - 测试思路：mock Feign → 断言传入 skuId 正确、响应映射正确。Fail 边界：URL/路径配错 → 删除功能整体 500

- 任务项 7.3.3：删除逻辑：订单计数 > 0 → 400「已产生订单的商品禁止删除」且不删；计数 = 0 → 删除 tb_sku 记录（操作日志 tb_sku_log 保留），shopId 隔离
  - 测试思路：mock Feign 返回 2 → 断言 400 且 delete 未被调用；返回 0 → 断言记录消失。Fail 边界：不校验直接删 → 历史订单关联到不存在的商品

- 任务项 7.3.4：order-service 不可用（Feign 调用失败）→ 删除返回 500/503，**宁可拒绝删除也不放行**
  - 测试思路：mock Feign 抛异常 → 断言响应 5xx 且 DB 中商品仍在。Fail 边界：吞异常放行删除 → 数据完整性破坏

## 功能点 8：真实销量回写（复用 RocketMQ PAY_SUCCESS 事件）

### 功能上下文 8.1：product-service 消费者（RocketMQ Listener）

- 任务项 8.1.1：消费 tag=PAY_SUCCESS 事件（载荷含 shopId、orderId、skuId、quantity 等），按 skuId 累加 tb_sku.sales（sales = sales + quantity，不是覆盖赋值）
  - 测试思路：sales 原值 5，消费 quantity=3 的事件 → 断言 sales=8。Fail 边界：写成 sales=3（覆盖）→ 累计销量丢失

- 任务项 8.1.2：无效/未知事件（JSON 畸形、缺 skuId/quantity、未知 tag）→ 忽略并 warn 日志，不抛异常（沿用 shop-service 消费者约定，防 MQ 重试风暴）
  - 测试思路：投递畸形事件 → 断言无异常、sales 不变、无重试。Fail 边界：抛异常 → 消息堆积

- 任务项 8.1.3：消费幂等：同一 orderId 重复投递只累加一次（product.db 记录已处理 orderId，如 tb_sku_log 或专用去重表）
  - 测试思路：同一事件投递 2 次 → 断言 sales 只加一次。Fail 边界：MQ 至少一次语义下重复累加 → 销量虚高

---

## 架构与重构思考

### 组件间关系分析

```
商家浏览器 → Gateway 8081（token 鉴权）→ product-service（product.db）
              ├─ ProductListController（列表/筛选/分页）
              │     └─ SkuService → SkuMapper（动态 SQL）
              ├─ ProductBatchController（批量设置）
              ├─ ProductImportExportController（EasyExcel 导入/导出）
              ├─ ProductController（已有 create；新增 detail/update/status/delete）
              │     └─ OrderQueryClient (Feign) ──→ order-service（sku 订单计数，删除前校验）
              └─ SalesEventConsumer (RocketMQ) ← b2c-order-events / PAY_SUCCESS ← order-service
                    └─ tb_sku.sales 累加
```

关键耦合点与重构方向：

1. **tb_sku 是宽表、字段语义承载过多**（双价格、双销量、双库存、双类型、多值配送方式）：本需求内保持单表简单直接；若后续秒杀/卡券衍生独立库存或核销逻辑，再按「商品主体 + 类型扩展表」拆分（product_type 已是拆分预留点）。`delivery_methods` 逗号分隔不利于按单方式精确索引筛选，量大后升级为关联表 tb_sku_delivery。
2. **字段校验逻辑三处重复**（导入 6.1.1、编辑 7.1.2、批量 4.1.3）：测试全绿后抽取 ProductFieldValidator（或 Bean Validation 注解 + 分组校验），与 user 模块 ErrorCode 枚举风格一致，防止三处口径漂移（如库存非负只校验了两处）。
3. **导出表头 = 导入模板的单一来源**：必须收敛为一个列定义常量类（如 ProductExcelColumns），导出与导入共用；否则两端硬编码必然漂移，导出的文件无法直接作为导入模板。
4. **删除校验的跨服务一致性**：order-service 不可用时选择「拒绝删除」（任务 7.3.4），保证不误删有单商品；这是可用性换正确性的取舍。若后续删除频繁被下游拖垮，改为「删除标记（逻辑删除）+ 异步核对」。
5. **销量回写的最终一致与幂等**：MQ 至少一次投递必须消费幂等（8.1.3）；真实销量与订单数据存在秒级延迟，展示层可接受。若要求精确，重构为列表页通过 Feign 实时汇总（决策时已否决，因性能与耦合代价）。订单支付事件的 shopId/skuId/quantity 载荷契约建议后续收敛到 common 模块共享 DTO（Dashboard 需求同样存在此问题）。
6. **列表多条件动态 SQL 的性能**：现有 idx_sku_shop_id 单列索引在数据量大、筛选条件多时可能退化为回表过滤；若实测慢，按高频筛选组合补索引（如 shop_id+status、shop_id+product_type），并用 EXPLAIN + 满量计时验证（沿用 Dashboard 6.2 的三层断言法）。
7. **Controller 粒度**：现有 ProductController 已承担 create，本需求新增 4 个入口类可按职责拆（列表/批量/导入导出/单品），避免单类膨胀；CreateProductTemplate 的模板方法按 type 分发逻辑不受影响（type 语义扩为商品类别后 3-卡券需新增 CardProductComponent 或落到虚拟分支——建议测试先行覆盖分发边界）。

### 架构对上下文划分的影响

- **schema 变更独立成功能点 1 并置于最前**：所有后续 DAO/Service 测试都依赖新字段，字段定义不清则全部返工；且 SQLite 不支持 ALTER 加约束，必须先钉死表结构。
- **权限与隔离（功能点 2）先于数据功能**：shopId 不信任前端参数是贯穿每个 SQL 的安全契约，后续每个 DAO 测试都必须带 shopId 隔离断言（与 Dashboard 需求同一原则）。
- **DAO 层（3.1）与 Service/Controller 层（3.2）分开**：动态筛选 SQL 是最容易出错的部分，用真实 SQLite 临时库验证命中集合；VO 组装与参数校验用 standalone MockMvc + Mockito 快速穷举边界（沿用 product 模块既有 TDD 风格：表驱动 @ParameterizedTest + @MethodSource）。
- **导入导出按「数据组装」与「文件格式」分层**：EasyExcel 是外部依赖，列定义收敛后 IO 层薄薄一层，核心断言（分流、校验、保护 sales）全部压在 Service。
- **删除按事件流向切成 7.3.1→7.3.2→7.3.3→7.3.4 四个原子任务**：order-service 计数 SQL、Feign 契约、删除决策、容错策略各自独立可测，mock Feign 与真实库测试分离。
- **销量回写（功能点 8）依赖 order-service 已有事件**：只在 product-service 侧新增消费端，发布端复用 Dashboard 需求产出，消费/幂等/容错三个任务原子化。

### TDD 执行顺序建议

功能点 1（schema）→ 功能点 2（隔离，安全先行）→ 功能点 3（列表+筛选+分类下拉）→ 功能点 7（单品编辑/上下架/删除，含 order-service 计数接口）→ 功能点 4（批量设置）→ 功能点 5（导出）→ 功能点 6（导入）→ 功能点 8（销量回写）。每个任务项独立「红 → 绿 → 重构」小循环；先写红测试时确保零误过（与 Dashboard 拆解同一红线），DAO 用真实临时 SQLite 库、Controller 用 standalone MockMvc + Mockito。
