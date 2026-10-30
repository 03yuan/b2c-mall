# C 端商品列表页（商城商品分类列表）TDD 拆解文档

## 需求概述

来源 `mall_category_product_list.docx`。C 端（买家侧）商城商品分类列表页：**所有人可查看、免登录（权限：无权限要求）**，只展示状态正常（上架）的商品。①顶部商品分类导航栏——展示一级分类标签（所有店铺的分类名，分类名去重），默认选中【全部】标签，点击对应分类标签切换筛选、页面刷新、只展示该分类下上架商品；②商品卡片列表——商品主图（预览图）、商品名称（超长自动截断）、商品售价（红色字体、单位元）、商品销量（累计销量）、点击整张卡片跳转【商品详情页面】。需求描述栏另声明：支持按销量/价格排序。NFR 小节无显式条目，沿用项目既有约定（统一 `BaseResponseVO` 返回、分页上限、空数据不伪造）。

**架构背景**：Maven 多模块微服务（Gateway 8081 → product-service，商品数据归 product.db SQLite）。分层统一：`web`（Controller + VO + TokenFilter + GlobalExceptionHandler）→ `service` → `dao/mapper` + `dao/po`。现有 `GET /product/list`、`GET /product/categories` 为商家端接口（TokenFilter 强制登录 + shopId 隔离）；`tb_category` 为店铺私有分类（id/shop_id/name/parent_id/sort/status）；`tb_sku` 已有 `status`(0下架/1上架)、`sales`(真实成交，MQ 累加)、`fake_sales`(注水，商家配置)、`images`(多图逗号分隔)、`sort`(越大越靠前)、`price`(销售价)。网关白名单（`gateway` 的 `b2c.auth.white-urls` + WhiteUrlFilter/AuthFilter 责任链）与 product-service `TokenFilter.shouldNotFilter` 是**双端**鉴权放行点，现有仅放行 `/product/create`、登录注册与 swagger。

**已确认的决策**（2026-10-28 交互确认）：
- **分类与商品范围为「全平台聚合 + 按名去重」**：Tab = 全部店铺一级分类（`parent_id=0`）名称去重后的集合（跨店）；点击某 Tab 显示全平台该分类名下所有上架商品（跨店混合展示）
- **卡片「商品销量」口径 = 真实 + 注水**：前台销量 = `sales + fake_sales`（fake_sales 为 null 按 0），VO 只暴露合成值、不暴露 fake_sales 单项（商家配注水的目的就是 C 端展示）
- **排序交互 = 综合/销量/价格三项**：综合（默认，`sort DESC, id DESC`）、销量降序（按前台销量）、价格（升/降切换，`price_asc`/`price_desc`）
- **商品范围与分页**：只查 `tb_sku.status=1` 即可（不校验店铺本身状态）；列表分页返回（page/size 沿用现有口径 1~100），空数据返回空集合 + total=0

**技术决策**（自行定案）：
- 新增公开端点 `GET /product/mall/categories`（分类 Tab 数据）与 `GET /product/mall/products`（卡片列表），复用网关既有 `/product/**` 路由（不加新路由）；双端白名单新增 `/product/mall/**`
- C 端 Controller 不触碰 `RequestHolderComponent`（白名单路径无登录态，规避 NPE）
- 前端职责显式固化：默认选中【全部】Tab 高亮与点击切换、卡片跳转详情页路由、名称超长 CSS 截断、售价红色样式与 ¥ 符号——后端不做（后端仅返回 skuId 供跳转；商品详情页数据接口不在本期范围）
- 点 Tab 按**分类名**跨店过滤：`tb_category.name = 分类名 AND tb_category.status = 1` 的 id 集合 → `sku.category_id IN 集合`（Tab 名本身来自一级分类去重集合，故语义为「该一级分类名所覆盖的商品」）

---

## 功能点 1：C 端分类导航接口（全平台一级分类名，去重，公开免登录）

### 功能上下文 1.1：DAO 层 - CategoryMapper 新增 mall 聚合查询（真实 SQLite 验证）

- 任务项 1.1.1：新增 `selectMallTopLevelNames()`：`WHERE parent_id = 0 AND status = 1`，**不带 shop_id 条件**（跨店聚合，与现有 `selectEnabledByShopId` 的有意差异），`GROUP BY name` 去重
  - 测试思路：两个店铺各插入同名一级分类（如 shop1/shop2 都建「水果」）→ 断言结果只含一个「水果」。Fail 边界：漏 GROUP BY → 同名出现两次；误加 shop_id → 只剩单店数据

- 任务项 1.1.2：排序确定性：`ORDER BY MAX(sort) ASC, name ASC`（去重后 sort 取各店最大值，名称字典序兜底，保证分页/前端渲染稳定）
  - 测试思路：插入 sort 乱序、名称乱序的多个去重类目 → 断言返回顺序与 `MAX(sort), name` 规则一致，多次查询顺序不变。Fail 边界：无 ORDER BY → SQLite 返回物理顺序，同数据不同插入序结果不同

- 任务项 1.1.3：禁用分类（status=0）与子分类（parent_id≠0）不出现在结果
  - 测试思路：插入 status=0 一级分类、parent_id=5 的子分类 → 断言均不返回。Fail 边界：漏 status 条件 → 禁用类目上导航；漏 parent_id 条件 → 子分类混入一级导航

### 功能上下文 1.2：Controller 层（GET /product/mall/categories）

- 任务项 1.2.1：返回去重后的分类名数组（`List<String>`，只含 name；因跨店去重后无唯一 id，Tab key 用名称）
  - 测试思路：standalone MockMvc（mock CategoryMapper）断言 200、`BaseResponseVO.data` 为名称字符串列表、每项仅 name。Fail 边界：返回带 shop_id/id 的对象数组 → 前端 Tab key 无法稳定（同店同名 id 不同）

- 任务项 1.2.2：空数据（无启用一级分类）返回空数组而非 null
  - 测试思路：mapper 返回空列表 → 断言 `data` 为 `[]`。Fail 边界：返回 null → 前端 forEach 崩溃

- 任务项 1.2.3：免登录可访问：无 Authorization 头 → 200；方法内不引用 RequestHolderComponent
  - 测试思路：MockMvc 不带 token 请求 → 断言 200；代码静态约束：该 Controller 无 requestHolder 依赖。Fail 边界：被 TokenFilter 拦截 → 401；引用 requestHolder.get() → 无登录态 NPE

## 功能点 2：C 端商品卡片列表接口（公开、仅上架、分页、跨店）

### 功能上下文 2.1：网关 + 服务双端白名单放行（安全边界先行）

- 任务项 2.1.1：gateway `application.yml` 的 `b2c.auth.white-urls` 新增 `/product/mall/**`（Ant 风格匹配 mall 下所有路径）
  - 测试思路：不带 token 经 Gateway 访问 `/product/mall/categories` → 网关层放行并转发到 product-service（断言响应 200 而非 401）。Fail 边界：漏配 → 网关 AuthFilter 拦截，全线 401；配成 `/product/mall` 不带 `/**` → 子路径不被匹配

- 任务项 2.1.2：product-service `TokenFilter.shouldNotFilter` 新增 `request.getRequestURI().startsWith("/product/mall")` 放行；并确保 C 端 Controller 不触碰 RequestHolderComponent
  - 测试思路：直连服务端口不带 token 请求两个 mall 端点 → 200。Fail 边界：只配了网关白名单漏服务端 → 直连/限流旁路时 401；两者都配但 Controller 取登录态 → NPE

### 功能上下文 2.2：DAO 层 - SkuMapper 新增 mall 分页查询（真实 SQLite 验证）

- 任务项 2.2.1：查询恒强制 `status = 1`，**不接收 status 参数、不带 shop_id 条件**（跨店聚合，与商家端 `/product/list` 的 shopId 隔离为两条独立查询路径）
  - 测试思路：两店各插上架/下架商品 → 断言只返回上架商品且包含两店数据。Fail 边界：信任前端传 status → 可绕过只看的边界；误加 shop_id → C 端只出单店

- 任务项 2.2.2：categoryName 可选过滤：`sku.category_id IN (SELECT id FROM tb_category WHERE name = #{categoryName} AND status = 1)`，跨店（他店同名分类商品同样命中）
  - 测试思路：shop1/shop2 各建同名分类「水果」各挂 1 个上架商品 → 传 categoryName=水果 断言两店商品都返回。Fail 边界：过滤写成按 category_id 等值 → 只能命中单店单个类目；分类 status=0 时其商品不应命中

- 任务项 2.2.3：分页：total = 筛选命中总数；LIMIT/OFFSET 用 `(page-1)*size`；page≥1、size 限 1~100，非法值拒绝（400）而非落 SQL 异常（沿用 SkuQuery setter 校验模式）
  - 测试思路：13 条上架商品 size=5 → 断言 total=13、第 1 页 5 条、第 3 页 3 条、第 4 页空；page=0 / size=0 / size=101 → 断言 IllegalArgumentException → 全局异常转 400。Fail 边界：offset 算错 → 翻页重复/漏行；校验缺失 → page=0 落到 SQLite LIMIT -5 语义异常

- 任务项 2.2.4：空结果返回空集合 + total=0，不伪造任何数值（沿用 Dashboard 约定）
  - 测试思路：无上架商品 → 断言 list 为空集合、total=0。Fail 边界：填充演示数据/占位图 → 用户明确反感虚假数据

### 功能上下文 2.3：Controller/Service 层（GET /product/mall/products，新 MallProductController）

- 任务项 2.3.1：新建 `MallProductController`（`@RequestMapping("/product/mall")`，无 requestHolder 依赖）；参数 categoryName/sortBy 可选、page/size 校验（非法 → 400）
  - 测试思路：standalone MockMvc 传 page=0 断言 400；合法参数断言 200。Fail 边界：把 mall 接口塞进依赖登录态的 ProductListController → 无登录态 NPE

- 任务项 2.3.2：VO 组装：每张卡片含 skuId、thumbnail（images 首图）、name（全名）、price（销售价）、displaySales（前台销量）
  - 测试思路：mock Service 返回样例 PO → 断言 VO 五要素齐全且值与 PO 对应。Fail 边界：漏 skuId → 前端无法跳详情；sales 单独返回而没合成 → 口径错误暴露注水字段

- 任务项 2.3.3：字段白名单：不返回库存（stock/storeStock）、fakeSales 单项、deliveryMethods、productType、type、status、marketPrice 等商家字段
  - 测试思路：断言响应 JSON 无上述键。Fail 边界：整 PO 透传 → C 端可窥探商家库存/注水配置，越界泄露

- 任务项 2.3.4：下架商品恒不返回；无图商品 thumbnail=null 不伪造占位图
  - 测试思路：混插上架有图/上架无图/下架 → 断言只出前两条且无图的 thumbnail 为 null。Fail 边界：返回整串 images → 前端 img 标签解析失败；无图造假图 URL → 与「不伪造」约定冲突

## 功能点 3：排序（综合默认 / 销量降序 / 价格升降）

### 功能上下文 3.1：DAO 层 - 动态 ORDER BY 片段（真实 SQLite 验证）

- 任务项 3.1.1：COMPREHENSIVE（默认）= `sort DESC, id DESC`（数字越大越靠前，同 sort 新商品在前）
  - 测试思路：插入 sort=3/1/2 与两条同 sort 的上架商品 → 断言返回顺序 3→2→1、同 sort 内 id 降序。Fail 边界：只按 id 排 → sort 字段失效；同 sort 稳定序缺失 → 翻页重复/漏行

- 任务项 3.1.2：SALES = `(sales + COALESCE(fake_sales, 0)) DESC, id DESC`（按前台销量降序）
  - 测试思路：插入 (sales=10,fake=5)、(sales=12,fake=null)、(sales=1,fake=100) → 断言顺序为 卖101、卖15、卖12（按合成值）。Fail 边界：只按 sales 排 → 注水商品沉底，违背「真实+注水」口径；fake_sales=null 未 COALESCE → 合成值为 null 排序错乱

- 任务项 3.1.3：PRICE_ASC = `price ASC, id DESC`；PRICE_DESC = `price DESC, id DESC`（BigDecimal 数值排序，非字符串排序）
  - 测试思路：插入 9.9 / 99 / 100 三条 → asc 断言 9.9→99→100、desc 断言 100→99→9.9。Fail 边界：price 列被按字符串排序（'100' < '99'）→ 顺序错误，需断言 DB 列为 NUMERIC

- 任务项 3.1.4：ORDER BY 只能在四种枚举白名单内拼接（`sortBy` 枚举映射 SQL 片段），杜绝 SQL 注入
  - 测试思路：枚举外值传不进 DAO（编译期），尝试拼接非法串断言被参数校验拦截。Fail 边界：字符串直接拼 SQL → `${sortBy}` 注入面

### 功能上下文 3.2：Controller - sortBy 参数校验

- 任务项 3.2.1：sortBy 缺省 → COMPREHENSIVE
  - 测试思路：不传 sortBy 断言 DAO 收到 COMPREHENSIVE。Fail 边界：缺省 null 拼进 ORDER BY → SQL 语法错误 500

- 任务项 3.2.2：合法值 `COMPREHENSIVE / SALES / PRICE_ASC / PRICE_DESC`（大小写不敏感可另议，默认严格大写）；其他值 → 400
  - 测试思路：表驱动传 5 个非法值（含 ''、'price'、'SORT' 混写等）断言 400；4 个合法值断言 200。Fail 边界：非法值被默默当作综合 → 前端传错静默降级难排查

## 功能点 4：分类 Tab 切换筛选语义（全部 / 按名跨店过滤）

### 功能上下文 4.1：Service/DAO - categoryName 过滤语义

- 任务项 4.1.1：不传 categoryName（等价「全部」Tab）→ 返回全平台全部上架商品
  - 测试思路：两店各 2 个上架商品不传参数 → 断言 total=4 且含两店数据。Fail 边界：无参时误带 shopId 或其他默认筛选 → 集合缺失

- 任务项 4.1.2：传 categoryName → 只含该分类名下上架商品；他店同名分类商品同样命中（跨店聚合口径的落地）
  - 测试思路：shop1「水果」挂 2 品、shop2「水果」挂 1 品、shop2「蔬菜」挂 1 品 → 传 水果 断言 total=3、蔬菜商品不出现。Fail 边界：只查单店分类 id → total=2（丢失他店）；写反为「排除该分类」→ 结果相反

- 任务项 4.1.3：不存在的分类名 → 空列表 + total=0，不抛异常（500）
  - 测试思路：传「不存在的分类XYZ」断言 200、list 空、total=0。Fail 边界：子查询无结果时抛 NPE/越界 → 用户点到一个商品已清空的 Tab 页面崩溃

### 功能上下文 4.2：接口契约 - 默认「全部」与前后端边界

- 任务项 4.2.1：接口契约固化：categories 返回的数据**不含「全部」占位项**；products 不传 categoryName 即全部——前端据此渲染默认高亮的「全部」Tab（选中态、点击刷新为前端职责）
  - 测试思路：断言 categories 结果中无「全部」字面量；断言 products 无参行为与传任意合法分类行为互斥且覆盖完整（全部 = 各分类之和）。Fail 边界：后端塞「全部」占位 → 前端点它传 categoryName='全部' 查不出任何商品（子查询空）

## 功能点 5：卡片字段语义（主图/名称/价格/前台销量/跳转信息）

### 功能上下文 5.1：DAO/PO - 前台销量计算列

- 任务项 5.1.1：mall 查询 SELECT 增加计算列 `(sales + COALESCE(fake_sales, 0)) AS display_sales`，SkuPO 增加只读 `displaySales` 属性映射（或 Mall 专用结果 PO，避免污染商家端 PO）
  - 测试思路：sales=10/fake=5 → displaySales=15；fake=null → 10；与 3.1.2 的排序表达式同源。Fail 边界：VO 层单算而排序层另算 → 口径漂移（前端「销量榜」第一名与卡片销量数字对不上）

### 功能上下文 5.2：Controller/VO - 字段语义钉死

- 任务项 5.2.1：thumbnail = images 首图（逗号分隔取第一张，复用/提取现有 `firstImage` 逻辑）；无图返回 null
  - 测试思路：images='a.jpg,b.jpg' → thumbnail='a.jpg'；images=null/'' → null。Fail 边界：取图逻辑在商家端/C 端两处复制 → 行为漂移（提取公共方法以测试锁定）

- 任务项 5.2.2：name 返回完整名称，后端不做截断（超长自动截断为前端 CSS `text-overflow` 职责）
  - 测试思路：80 字名称 → 断言返回全 80 字未被裁剪。Fail 边界：后端截 20 字 → 详情/搜索/无障碍读屏拿不到全名，且与需求「文字超长自动截断」的展示层语义冲突

- 任务项 5.2.3：price 以数值（BigDecimal，单位元）原样返回；红色字体与 ¥ 符号为前端样式职责，后端不做格式化字符串
  - 测试思路：price=88.00 → 断言返回数值 88.00 而非 '¥88.00'/'<font color=red>…'。Fail 边界：后端返回带样式/货币符字符串 → 前端无法复用数值（加购、合计、排序全部受影响）

- 任务项 5.2.4：displaySales 只用合成值；fakeSales 为 null 按 0 计（5.1.1 兜底），VO 不暴露真实/注水分项
  - 测试思路：sales=0、fake_sales=null → displaySales=0（而非 null）；响应 JSON 无 fakeSales 键（2.3.3 联动）。Fail 边界：返回 null → 前端显示「null 件已售」；暴露 fakeSales → 买家看穿注水

- 任务项 5.2.5：返回 skuId 供前端构造卡片跳转链接（如商品详情页路由 `?skuId=`）；商品详情数据接口不在本期范围
  - 测试思路：断言 VO 含 skuId 且值为 PO.id；无需详情接口（范围外，防镀金）。Fail 边界：本期顺手写详情接口 → 越界交付、拖慢 TDD 节奏且无测试锚点

---

## 架构与重构思考

### 组件间关系分析

```
买家浏览器 → Gateway 8081（WhiteUrlFilter 白名单 /product/mall/** → AuthFilter 跳过鉴权）
              └─ /product/** 路由 → product-service
                    ├─ TokenFilter（shouldNotFilter 放行 /product/mall，不注入登录态）
                    ├─ MallProductController（公开，不依赖 RequestHolderComponent）
                    │     ├─ GET /product/mall/categories → CategoryService → CategoryMapper.selectMallTopLevelNames()
                    │     └─ GET /product/mall/products   → SkuService → SkuMapper 新增 mall 分页查询
                    └─ 商家端（登录态）：ProductListController /product/list → shopId 隔离查询
                    两者共用 product.db 的 tb_sku/tb_category，读视角不同（平台聚合 vs 店铺隔离）
```

关键耦合点与重构方向：

1. **双端白名单易漏配**（网关 `white-urls` 与服务 `TokenFilter` 两处必须同步加 `/product/mall/**`，漏一处则「网关放行、服务拦 401」或反之）：测试全绿后建议把公开路径清单收敛到 Nacos 共享配置，由 gateway 与各服务 TokenFilter 共同读取，消除两处漂移的维护成本（本次先按现状双改并用 2.1.1/2.1.2 两级测试钉住）。
2. **「同名即同类」的聚合约定**：`GROUP BY name` 是全平台分类聚合的根基，隐含约定为「分类名全局唯一代表一个品类」。当前各店分类自由创建，若后续分类树变深（子分类挂商品），按一级名过滤将漏掉子分类商品（本期按需求字面：一级分类导航，已知边界）；重构方向为平台级分类目录（tb_platform_category + 店铺类目映射）或商品冗余「品类名」字段，届时 1.1.1 与 2.2.2 的 SQL 收敛到一处。
3. **前台销量口径两处落地**（商家端列表列、C 端卡片与销量排序）：`sales + COALESCE(fake_sales, 0)` 的计算若分处实现必然漂移。建议提取单点（如 SkuDisplaySales 工具或 SQL 视图/计算列由 DAO 统一返回 display_sales），商家端与 C 端共用，任务 5.1.1 已先行在 DAO 侧锁定。
4. **同一 SkuMapper 承载两条语义相反的查询路径**（商家端恒带 shop_id、C 端恒不带）：属有意设计，但 code review 防误加/误删 shop_id 的成本高；若后续 C 端读流量显著增长，可演进为独立的 `MallSkuMapper` 或 CQRS 只读视图（tb_sku_read 投影 + 异步同步），避免读模型与商家写模型互相牵制。
5. **全平台列表查询的索引现状**：现有 `idx_sku_shop_id`/`idx_sku_category_id` 对「全平台 status=1 + sort/price 排序」帮助有限；SQLite 小库当前可接受，若商品量大建议补 `(status, sort)`、`(status, price)` 组合索引，并以 EXPLAIN QUERY PLAN + 满量计时（沿用项目三层断言法）验证后决定，不预加。
6. **分页/排序查询对象的重叠**：`SkuQuery`（商家端）与新 `MallSkuQuery` 的 page/size 校验、offset 计算同构——重复两处易漂移；重构方向是提取 `PageQuery` 基类（含 1~100 边界校验），两查询对象继承，测试重点压边界类即可。

### 架构对上下文划分的影响

- **安全边界（功能点 2.1）先于一切数据功能**：`/product/mall/**` 是整个项目第一条「公开读」路径，与既有「店铺写/读都隔离」的模型形成对比；双端白名单是后续所有 C 端测试能跑到绿的前提（白名单漏配时连最简单的 categories 测试都是 401），故拆解中单列功能点并置顶执行。
- **DAO 层（1.1/2.2/3.1）与 Controller/VO 层分开**：跨店聚合 SQL 的命中集合（去重、名匹配、排序、分页）最容易错，用真实 SQLite 临时库断言数据全集；VO 组装、参数校验、字段白名单用 standalone MockMvc + Mockito 快速穷举边界（沿用 product 模块既有 TDD 风格：表驱动 `@ParameterizedTest` + `@MethodSource`）。两者若合在一层测试，Mock 边界会吃掉 SQL 错漏。
- **排序独立成功能点 3**：ORDER BY 动态片段是拼接安全（3.1.4 枚举白名单）+ 数值语义（BigDecimal 而非字符串）+ 稳定序三件事的叠加，每个排序模式一个原子任务，互不牵制；脱离列表/字段语义单独成点便于先红后绿逐步暴露缺陷。
- **前端职责显式固化（4.2/5.x）**：需求中「默认选中」「红色字体」「自动截断」「跳转」是纯展示行为，TDD 的边界若不写死，极易被后端过度实现（返回 HTML 样式串、截断字符串）。文档以任务项形式把「后端契约」与「前端职责」切开，测试只断言后端契约。
- **Tab 切换语义（功能点 4）与列表查询（2.2.2）拆为两个上下文**：SQL 实现同一个子查询，但「全部 vs 过滤」的互斥完整性（4.2.1）是接口契约层的事，需从 Service/Controller 视角单独穷举，防止实现者把「无参」悄悄加默认分类。

### TDD 执行顺序建议

功能点 2.1（双端白名单，否则后续测试全线 401）→ 功能点 1（分类导航，数据模型最简单、先跑通公开接口链路）→ 功能点 2.2 → 2.3（列表分页 + VO，5.2 卡片字段语义与 2.3 同轮收口）→ 功能点 3（排序，依赖 2.2 的查询骨架）→ 功能点 4（Tab 过滤语义，依赖 2.2.2 与 1.x 的结果集）→ 功能点 5.1（display_sales 计算列若作为 3.1.2 前置可提前至 3 之前）。每个任务项独立「红 → 绿 → 重构」小循环；红阶段确保测试真实失败且失败原因唯一（缺实现而非断言写反），DAO 用真实临时 SQLite 库、Controller 用 standalone MockMvc + Mockito，与 shop-product-list / shop-order-list 两份拆解同一红线。