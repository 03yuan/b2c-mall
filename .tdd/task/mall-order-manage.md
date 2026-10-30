# C 端订单管理模块（买家订单管理中心）TDD 拆解文档

## 需求概述

来源 `mall_order_manage.docx`。目标用户：C 端买家。权限：登录账号才可访问，只可见/可操作当前用户自己的订单数据。

页面组成：

- **订单状态标签（顶部 Tab）**：全部订单 / 待付款 / 待发货 / 待收货 / 已完成，括号内展示该状态订单数量（示例：待付款 0、待发货 9、已完成 10）。
- **筛选与查询区域**：订单编号输入框（提示文字「请输入订单编号查询」）+ 红色【查询订单】按钮，按订单号**精确**检索。
- **订单卡片**（一条订单一个卡片）：
  - 头部：订单编号、店铺名称、店铺图标、电话图标、右侧删除图标；
  - 商品行：商品图片、商品名称、数量 xN、单价 ¥xxx.00、总额（同订单合并金额）、订单整体状态文案；待发货卡片附带支付方式（如「余额支付」）；
  - 操作按钮：按订单状态动态展示。
- **操作**：订单详情（弹窗/新页，含完整明细、物流、支付记录）、立即购买（复购，跳商品下单页）、删除订单（仅隐藏，后台保留数据）、收藏订单。

**架构背景**：Maven 多模块微服务（Gateway 8081 → order-service，订单数据存 `order.db` SQLite，schema 变更必须先 DROP 旧表重建）。order-service 分层统一：`web`（Controller + Request/VO + GlobalExceptionHandler + TokenFilter）→ `service` → `dao/mapper` + `dao/po`。既有模式：支付策略（ALIPAY/WECHAT/BALANCE 模拟实现）、订单状态机（PENDING→PAID→SHIPPED→COMPLETED，另含 FAILED 终态、GROUP_PENDING 预留）、事件发布（PAY_SUCCESS / CONFIRM_RECEIPT）。TokenFilter 双身份：`/order/buyer/**` 与 `/order/create` 要求买家身份，其余要求商家身份。

**与已有拆解的关系**：本需求是 `shop-order-list.md` 功能点 10/11（买家列表/详情/删除/取消）的独立深化版，该部分代码已实现。本文档**完整覆盖**本需求全部功能点，每个任务项标注【已实现】/【新增】，已实现项给出回归口径的测试思路。

**本次交互确认决策（2026-10-29）**：

- 收藏 = **收藏订单**：tb_order 新增买家收藏标记字段 `buyer_fav`，提供收藏/取消收藏接口，列表卡片展示是否已收藏；收藏**不参与**状态 Tab 计数与筛选（「收藏订单」筛选 Tab 列为重构方向）。
- 电话图标可交互（点击展示/拨打店铺电话），数据来源为**订单快照冗余 `shop_phone`**；店铺 LOGO 由前端占位展示，无后端要求。
- 各状态按钮集合（按钮显隐由前端基于卡片 `status` 原始枚举自行控制，后端只提供原子接口）：

| 订单状态 | 前端按钮 | 后端接口 | 现状 |
|---|---|---|---|
| 待付款 PENDING | 去支付 / 取消订单 / 删除 | 去支付【新增】、取消【已实现】、删除【已实现】 | 去支付缺 |
| 待发货 PAID | 订单详情 / 立即购买 / 删除 | 详情【已实现】、删除【已实现】，立即购买=前端跳转 | 齐 |
| 待收货 SHIPPED | 确认收货 / 订单详情 / 立即购买 / 删除 | 确认收货【新增】，其余齐 | 确认收货缺 |
| 已完成 COMPLETED | 订单详情 / 立即购买 / 删除 | 齐 | 齐 |
| 已取消 FAILED | 立即购买 / 删除 | 齐 | 齐 |

**新增字段映射**（需求 → tb_order）：

| 需求点 | 字段 | 说明 |
|---|---|---|
| 收藏订单 | `buyer_fav` | TINYINT DEFAULT 0，0-未收藏 1-已收藏 |
| 电话图标 | `shop_phone` | VARCHAR(20) 可空，下单快照冗余 |
| 店铺名称头部（已建未写） | `shop_name` | 已有列，下单路径补齐写入 |
| 商品图片（已建未写） | `sku_image` | 已有列，下单路径补齐写入 |
| 成交单价（已建未写） | `unit_price` | 已有列，下单路径补齐写 price |

**现状债**：`createOrder` 目前只落基础字段，`shop_name`/`sku_image`/`unit_price` 三个快照列建而未写（恒 null），导致卡片头部店铺名、商品图片、单价为 null——功能点 5 一并补齐。

---

## 功能点 1：tb_order schema 扩展（buyer_fav / shop_phone）【新增·前置】

### 功能上下文 1.1：DAO/PO 层 - 表结构与实体

- 任务项 1.1.1：`order.sql` 的 tb_order 新增列 `buyer_fav TINYINT DEFAULT 0`、`shop_phone VARCHAR(20)`（可空）；沿既有规则「先 `DROP TABLE IF EXISTS tb_order` 再重建」（SQLite 的 CREATE IF NOT EXISTS 不改旧表）
  - 测试思路：真实 SQLite 临时库执行 order.sql → 插入一条含 buyer_fav=1、shop_phone='13800000001' 的订单 → 逐字段读回断言一致。Fail 边界：漏字段 → `no such column`；忘 DROP 旧表 → 新字段静默丢失、存量库查新列报错

- 任务项 1.1.2：OrderPO 同步新增属性 `buyerFav`（Integer）、`shopPhone`（String）
  - 测试思路：insert 后 selectById 断言两属性与写入值一致。Fail 边界：PO 漏属性 → MyBatis 映射丢列，卡片 fav/电话恒 null

- 任务项 1.1.3：默认值约束：未显式传入时 `buyer_fav=0`（shop_phone 可空无需默认值）
  - 测试思路：按现有 insert 列清单（不带新字段）插入 → 读回 buyer_fav 断言为 0 而非 null。Fail 边界：默认值缺失 → 卡片「是否已收藏」判断 null 与 false 混淆

- 任务项 1.1.4：反向决策固化——本期不为 buyer_fav/shop_phone 建索引（列表不按收藏过滤；若未来加「收藏 Tab」需 `(buyer_username, buyer_fav)` 复合索引，届时走三层断言法）
  - 测试思路：无 SQL 断言；在 order.sql 加注释与本文档互相引用，防止未来误加无谓索引或漏加必须索引

## 功能点 2：登录态与买家数据隔离【已实现·回归保障】

### 功能上下文 2.1：Filter 层 - TokenFilter 双身份校验（order-service）

- 任务项 2.1.1：`/order/buyer/**` 全部接口要求买家身份（token 载荷含 userId/username），无 token/无效 token/商家 token 访问 → 401
  - 测试思路：三态表驱动——无 token、伪造 token、商家 token 各调一个买家接口 → MockMvc 断言 401「未登录或登录态无效」。Fail 边界：Filter 回归为透传 → 任何人可看别人订单

- 任务项 2.1.2：买家接口身份恒取登录上下文，请求参数中的 buyerUsername/username 一律忽略
  - 测试思路：MockMvc 带 `?buyerUsername=他人` 调用 → Mockito `verify(only())` 断言 Service 仅以登录 username 被调用。Fail 边界：信任前端参数 → 水平越权

### 功能上下文 2.2：Controller + DAO 层 - 隔离条件硬编码

- 任务项 2.2.1：买家侧所有查询/更新 SQL 恒带 `buyer_username = 登录username AND buyer_deleted = 0`（复用 `buyerQueryWhere` 共用片段，见 4.1.1）
  - 测试思路：真实临时库插入买家 A、B 订单各若干 + A 的 1 条已删订单 → A 登录查询断言不含 B 订单、不含已删订单。Fail 边界：漏 buyer_deleted → 已删除订单复活；漏 buyer_username → 看到他人订单

- 任务项 2.2.2：跨身份 id 防护：收藏/支付/确认/取消/删除传入他人 orderId → 404 且目标数据零改动（各操作功能点内重复断言）
  - 测试思路：买家 A 对买家 B 订单执行任一写操作 → 404，B 订单 DB 快照逐字段不变。Fail 边界：UPDATE 漏身份条件 → 改到他人数据

## 功能点 3：订单状态 Tab 与状态计数【已实现·回归】

### 功能上下文 3.1：Service + Controller 层（GET /order/buyer/status-counts）

- 任务项 3.1.1：返回结构固定五个 key：all/pending/paid/shipped/completed，无订单的状态返回 0 而非缺失（不用 GROUP BY 结果直出）
  - 测试思路：买家 A 造数覆盖 5 种状态 + 1 条已删订单 → 断言各 key 数值与示例形态一致（如 待发货=9）；已删订单不计入任何 key。Fail 边界：key 缺失 → 前端括号显示 undefined；已删计入 → 计数与列表不一致

- 任务项 3.1.2：「全部」= 其余各状态之和（含 FAILED/GROUP_PENDING），与列表默认（不传 status）条数一致
  - 测试思路：all 断言 = 不传 status 的 page 接口 total；含 FAILED 单时 all 大于四状态之和。Fail 边界：口径漏 FAILED → Tab 总数少于列表实际条数

- 任务项 3.1.3：Tab 点击切换为前端行为：切换只调用列表接口 `GET /order/buyer/page?status=xxx`，后端无需 Tab 专用接口
  - 测试思路：断言 status 参数与 BuyerOrderQueryPO 等值映射（表驱动 PENDING/PAID/SHIPPED/COMPLETED/空）。Fail 边界：新增未知状态 → 列表返回空而非 500（未知状态原样回传契约，沿用商家端口径）

## 功能点 4：订单编号查询与卡片数据组装【已实现 + 新增两字段】

### 功能上下文 4.1：DAO 层 - 买家分页查询

- 任务项 4.1.1：`selectBuyerPage` / `countBuyerByFilter` 共用 WHERE 片段：buyer_username 恒置、buyer_deleted=0 恒置；status 可选等值；order_no **精确匹配**（不做模糊）；排序 create_time DESC, id DESC；分页边界同商家端（page≥1、1≤size≤100）
  - 测试思路：真实临时库（买家 A 8 条多状态含 1 条已删 + 买家 B 2 条陷阱 + 1 条编号为包含关系前缀的单）→ 各筛选双断言（rows + total 同源）；`order_no=部分编号` 不命中；已删订单在任何筛选下不出现。Fail 边界：精确变模糊 → 输前缀命中无关订单；分页越界 → SQL 报错或无数据

### 功能上下文 4.2：Service/Controller 层 - 卡片 VO 组装（GET /order/buyer/page）

- 任务项 4.2.1：卡片基础字段【已实现】：orderNo、shopName、createTime、status（原始枚举）+ statusText（中文文案）、payType（仅待发货 PAID 卡片附带）、items[{skuImage、skuName、quantity、unitPrice}]（数组结构向前兼容多商品）、totalPrice、skuId（立即购买跳转用）
  - 测试思路：断言卡片字段齐全；PAID 卡片含 payType、其他状态卡片不含；items 为数组且 quantity/unitPrice 与库一致。Fail 边界：缺 skuId → 立即购买无法跳转；缺 status 原始枚举 → 前端按钮矩阵无法派发

- 任务项 4.2.2：卡片新增 `shopPhone`（电话图标数据源）【新增】：读取 tb_order.shop_phone 快照；null 时输出空串（沿用 buyerUsername 空化规则），前端按空隐藏图标
  - 测试思路：库中 shop_phone 有值 → 卡片原样透出；null → 空串而非 null。Fail 边界：null 直出 → 前端 undefined 判断漂移

- 任务项 4.2.3：卡片新增 `buyerFav`（收藏标记）【新增】：读取 tb_order.buyer_fav，恒输出 0/1
  - 测试思路：已收藏订单 → 1；未收藏 → 0；（委托断言）他人订单不可见由 4.1.1 覆盖。Fail 边界：字段缺失 → 收藏按钮态无法刷新

- 任务项 4.2.4：编号查询契约：orderNo 为空 → 等同于全部列表；未命中 → list 为空数组（非 null）+ total=0
  - 测试思路：MockMvc 断言空条件返回全量、假编号返回 `{"list":[],"total":0}`。Fail 边界：空结果返回 null → 前端渲染崩溃

## 功能点 5：下单快照补齐（店铺名称/电话/商品图片/成交单价）【新增】

> 目的：卡片头部店铺名称、电话图标、商品图片、单价目前恒 null（快照列建而未写）。补齐写入路径，向前兼容老请求。

### 功能上下文 5.1：Web 层 - CreateOrderRequest 扩展

- 任务项 5.1.1：请求体新增**可选**字段 shopName、skuImage、shopPhone（不加校验注解；unitPrice 复用已有 price 字段写入）
  - 测试思路：带/不带新字段两种请求均 200 创建成功；带非法类型（数字传字符串字段）按既有严格类型反序列化规则 400。Fail 边界：误加 @NotBlank → 老客户端请求全被 400 拒绝

### 功能上下文 5.2：Service 层 - createOrder 写入快照

- 任务项 5.2.1：createOrder 落库时写入 shop_name=shopName、sku_image=skuImage、shop_phone=shopPhone、unit_price=price（与 sku_name 同「信任请求快照」策略，不做店铺/商品校验）
  - 测试思路：带 shopName/skuImage/shopPhone 创建 → DB 读回四字段一致，unit_price 与 price 相等（BigDecimal compareTo）；随后调买家列表断言卡片头部与商品行不再是 null。Fail 边界：快照不写 → 电话图标点击无号、店铺名空白、单价 undefined

- 任务项 5.2.2：新字段为 null/空串时落库 null 且创建流程不报错（向后兼容存量前端调用方）
  - 测试思路：仅旧字段的请求 → 201，shop_phone/sku_image 等为 null，列表返回空串。Fail 边界：null 处理崩溃 → 老客户端下单全线失败

## 功能点 6：收藏订单【新增】

### 功能上下文 6.1：DAO 层 - 收藏/取消收藏 SQL

- 任务项 6.1.1：收藏 `UPDATE tb_order SET buyer_fav=1, update_time=now WHERE id=? AND buyer_username=? AND buyer_deleted=0`（affect 0 行=无权/不存在/已删）
  - 测试思路：真实临时库——本人单 → 1 行且 fav=1；他人单 → 0 行零改动；已删单 → 0 行零改动。Fail 边界：漏 buyer_username → 用户可收藏任意订单；漏 buyer_deleted → 已删订单被复活操作

- 任务项 6.1.2：取消收藏同构 SQL 置 0，幂等（重复取消返回成功、零影响亦可）
  - 测试思路：对未收藏单执行取消 → 200 幂等成功、fav 仍 0。Fail 边界：重复取消报错 → 前端双击崩溃

### 功能上下文 6.2：Controller + Service 层 - 收藏/取消收藏接口

- 任务项 6.2.1：`POST /order/buyer/fav/{orderId}`：本人且未删除订单 → 200 + DB fav=1 + 列表卡片 buyerFav=1；他人/不存在/已删除 → 404
  - 测试思路：MockMvc 断言状态码与响应体；Service 层配真实库断言落库；四分支表驱动（本人/他人/不存在/已删）。Fail 边界：他人订单 200 → 越权写

- 任务项 6.2.2：`POST /order/buyer/unfav/{orderId}`：同上契约，fav 置 0；幂等
  - 测试思路：收藏后取消 → 200、卡片 buyerFav=0；对他人订单取消失败 404。Fail 边界：取消接口与收藏接口共享 SQL 却 copy-paste 错列 → fav 恒 1

- 任务项 6.2.3：收藏不改变状态机、不写 tb_order_log、不影响状态计数（纯标记字段）
  - 测试思路：对 PENDING 单收藏后断言 status 仍 PENDING、log 表无新行、status-counts 不变。Fail 边界：收藏误触状态流转 → 订单状态异常

## 功能点 7：待付款 → 买家去支付【新增】

> 按钮矩阵第 1 位；复用商家支付链路（策略 + 状态机 + 事件），仅前置买家归属校验。

### 功能上下文 7.1：Service 层 - payByBuyer 复用重构

- 任务项 7.1.1：提取现有 `pay` 的支付主体为私有方法 `doPay(order, payType, operatorId)`（策略选型 + transitionState("PAY") + pay_type/pay_time 落库 + PAY_SUCCESS 事件），商家 pay 与买家 payByBuyer 共用，杜绝口径分叉
  - 测试思路：重构后跑既有 OrderBalancePayTest 全绿（18 场景回归）；静态断言 OrderServiceImpl 仅一处 transitionState("PAY") 调用点。Fail 边界：复制新实现 → pay_time 漏写/事件漏发口径漂移

- 任务项 7.1.2：`payByBuyer(orderId, payType, username)`：先以 `id + buyer_username + buyer_deleted=0` 查归属，无 → 404；本人 PENDING 单 → doPay 成功，DB 落 pay_type/pay_time；operatorId 传买家语义（username 或 0，写日志审计用）
  - 测试思路：真实库 PENDING 单 → 断言 status=PAID、pay_type=BALANCE、pay_time 非空、日志 event=PAY、PAY_SUCCESS 事件载荷含 skuId/quantity；他人单 → 404 零改动。Fail 边界：归属校验放 for 循环/事件后 → 越权支付他人订单

- 任务项 7.1.3：状态边界：非 PENDING（重复支付 PAID / FAILED / SHIPPED / COMPLETED）→ 拒绝且订单零改动；payType 非法/未注册策略 → 400「不支持的支付方式」
  - 测试思路：表驱动 4 状态 + 非法 payType → 断言异常与状态不变。Fail 边界：重复支付成功 → 一单两笔支付

### 功能上下文 7.2：Web 层 - 接口契约（POST /order/buyer/pay/{orderId}?payType=xxx）

- 任务项 7.2.1：payType 必填（缺失 → 400 中文原因）；本人 PENDING → 200；他人/不存在 → 404；未登录/商家 token → 401
  - 测试思路：standalone MockMvc 表驱动 6 场景断言状态码与错误体；401 由 Filter 层测试覆盖（2.1.1）。Fail 边界：买家接口忘记挂 /order/buyer/** 前缀 → 商家也能支付买家订单

## 功能点 8：待收货 → 买家确认收货【新增】

### 功能上下文 8.1：Service 层 - confirmByBuyer 复用重构

- 任务项 8.1.1：提取现有 `confirm` 主体为 `doConfirm(order, operatorId)`（transitionState("CONFIRM") + CONFIRM_RECEIPT 事件），买家版 `confirmByBuyer(orderId, username)` = 归属校验（同 7.1.2 口径）+ doConfirm
  - 测试思路：真实库 SHIPPED 本人单 → COMPLETED、日志 event=CONFIRM、CONFIRM_RECEIPT 事件发布；重构后既有 OrderShipServiceTest 回归全绿。Fail 边界：买家版绕过状态机直接 UPDATE → 任意状态被确认

- 任务项 8.1.2：非 SHIPPED（PENDING/PAID/COMPLETED/FAILED）→ 状态机抛 IllegalStateException，Controller 层映射为 400 级中文错误；他人/已删 → 404 零改动
  - 测试思路：表驱动 4 非法状态 + 越权场景，断言订单快照不变。Fail 边界：PENDING 直接确认收货 → 跳过付款与发货环节

### 功能上下文 8.2：Web 层（POST /order/buyer/confirm/{orderId}）

- 任务项 8.2.1：本人 SHIPPED → 200 空 body；他人 → 404；未登录 → 401
  - 测试思路：MockMvc 三态断言（含真实库联动验证 COMPLETED）。Fail 边界：无买家归属校验 → 越权确认他店订单

## 功能点 9：待付款 → 买家取消订单【已实现·回归】

### 功能上下文 9.1：Controller + Service 层（POST /order/buyer/cancel/{orderId}）

- 任务项 9.1.1：仅 PENDING → FAILED，经状态机写 event=CANCEL 日志（from=PENDING, to=FAILED）；buyer_username + buyer_deleted=0 隔离
  - 测试思路：回归 OrderCancelServiceTest：PENDING → FAILED 且日志正确；对 FAILED 重复取消 → 抛异常（终态防护）。Fail 边界：回归改动导致 PAID 单被取消 → 绕过退款资金失控

- 任务项 9.1.2：PAID 取消 → 400 级错误提示「已付款订单请申请退款」；他人订单 → 404
  - 测试思路：表驱动断言状态码与 DB 零改动。Fail 边界：错误提示含糊 → 用户困惑投诉

## 功能点 10：订单详情（弹窗/新页数据源）【已实现·回归】

### 功能上下文 10.1：Controller + Service 层（GET /order/buyer/{orderId}）

- 任务项 10.1.1：本人订单 → OrderDetailVO 完整返回：行字段全量 + receiverAddress + logisticsNo/logisticsType + payTime + logs（状态流转时间升序）——满足需求「完整明细、物流、支付记录」
  - 测试思路：回归 OrderDetailServiceTest；对已支付+已发货单断言 logisticsNo/payTime/logs 非空齐全。Fail 边界：缺 payTime → 详情页支付记录空白

- 任务项 10.1.2：他人订单/不存在 → 404；未登录 → 401；弹窗/新页打开形态为前端职责（后端接口单一来源）
  - 测试思路：MockMvc 三态断言。Fail 边界：越权读他人订单泄露收货地址/电话

## 功能点 11：删除订单（逻辑删除）【已实现·回归】

### 功能上下文 11.1：Controller + Service 层（POST /order/buyer/delete/{orderId}）

- 任务项 11.1.1：逻辑删除 `buyer_deleted=1`，后台数据保留；删除后买家列表/计数/详情/一切买家操作接口均不可见不可操作；重复删除幂等 200
  - 测试思路：回归 BuyerOrderActionServiceTest：删除 → 列表/计数不含该单、DB 行仍在且 buyer_deleted=1、商家端列表仍可见（履约数据不丢）；未收藏收藏标记等其它字段保留原值。Fail 边界：物理 DELETE → 商家端历史订单丢失

- 任务项 11.1.2：他人订单删除 → 404 零改动
  - 测试思路：买家 A 删买家 B 单 → 404 且 B 单 buyer_deleted 仍 0。Fail 边界：漏身份条件 → 大规模数据破坏

## 功能点 12：立即购买（复购）【已实现·前端跳转】

### 功能上下文 12.1：数据契约层 - 卡片 VO 含 skuId

- 任务项 12.1.1：立即购买 = 前端拿卡片 `skuId` 跳转商品下单页重新下单，后端**无新接口**；契约保障点是卡片 VO 恒含 skuId
  - 测试思路：断言 BuyerOrderCardVO 组装 skuId 非空（与库 sku_id 一致）且不暴露多余字段（防御：跳转参数字段缺失即无法复购）。Fail 边界：skuId 漏组装 → 复购按钮死链

- 任务项 12.1.2：下单页为商品侧既有 mall 功能（mall-category-product-list 体系），立即购买不绕过商品有效性/店铺归属（长期债：下单接口不做商品校验，见重构思考）
  - 测试思路：无后端红测试；在跳转链路注释固化责任边界。Fail 边界：误在买方侧新增「立即购买接口」→ 与 create 重复下单逻辑分叉

---

## 架构与重构思考

### 组件间关系分析

```
买家浏览器 ─→ Gateway 8081（AuthFilter 验 Redis b2c:token:{token}）
   │
   └→ order-service（org.ysh.order）
        ├─ TokenFilter：/order/buyer/** 强制买家身份（username 载荷），其余商家身份（shopId 载荷）
        ├─ BuyerOrderController：
        │     GET  /order/buyer/page?status=&orderNo=          （列表+卡片，新增 shopPhone/buyerFav 字段）
        │     GET  /order/buyer/status-counts                   （五 key 计数）
        │     GET  /order/buyer/{orderId}                       （详情：明细+物流+支付记录+日志）
        │     POST /order/buyer/pay/{orderId}?payType=          （去支付【新增】）
        │     POST /order/buyer/confirm/{orderId}               （确认收货【新增】）
        │     POST /order/buyer/fav/{orderId} / unfav/{orderId} （收藏/取消收藏【新增】）
        │     POST /order/buyer/cancel/{orderId}                （取消：PENDING→FAILED）
        │     POST /order/buyer/delete/{orderId}                （逻辑删除）
        └─ OrderService → OrderMapper（buyerQueryWhere 共用片段：buyer_username + buyer_deleted=0）
             ├─ 状态机 OrderState（PAY/CONFIRM/CANCEL 流转 + 日志）
             ├─ 支付策略 PayStrategyService（BALANCE/ALIPAY/WECHAT）
             └─ OrderEventPublisher（PAY_SUCCESS / CONFIRM_RECEIPT）
user-service：登录写 Redis token（买家身份源头）
```

关键耦合点与重构方向：

1. **payByBuyer/confirmByBuyer 必须复用而非复制**：商家 `pay`/`confirm` 已含策略选型、状态机、pay_type/pay_time 落库、事件发布一整套口径（历史已修过 pay_time 漏写/事件风险）。买家版若复制实现，任何一次口径修正都会漏改一边。绿阶段即提取 `doPay`/`doConfirm` 私有方法，买家版 = 归属查询 + 委托，归属查询失败 404 与业务异常 400 分流清晰。
2. **快照列「建而未写」的债已到偿还点**：shop_name/sku_image/unit_price 三列恒 null 导致卡片头部与商品行残缺，本需求以「电话图标需 shop_phone」为契机把四个快照写入口径一次性补齐。**存量订单仍是 null**——数据回填需从 product 服务按 sku_id 拉商品信息，跨服务关联，单独立项，不在本需求混入。
3. **收藏标记落在 tb_order 的适用前提**：一单一买家模型下每单唯一 buyer_username，单字段够用且零 JOIN。若未来买家可分享订单/多端收藏口径变化，须拆 `tb_order_fav(order_id, buyer_username)` 关联表；「收藏订单独立 Tab」需求出现时再加 `(buyer_username, buyer_fav)` 复合索引并走三层断言法（1.1.4 已预留决策）。
4. **按钮矩阵是前端职责，后端只给原子接口**：卡片透传 status 原始枚举（既有契约，无 statusText 独断），后端不因「某个状态加按钮」再改接口——矩阵变化只动前端；后端变化只发生在「该状态操作无后端支撑」时（本次仅补去支付/确认收货两个缺口）。
5. **TokenFilter 三服务复制是持续债**：product/shop/order 各有一份双身份/单身份解析变体，本需求继续依赖 order 内这份；测试全绿后可沉淀 common 统一组件 + Gateway 注入身份头，403/401 双重校验也可收敛为 Filter 一处（现有 Controller 内重复 401 判断是防御性冗余，收敛后契约测试统一走 Filter）。
6. **下单「信任请求快照」策略的边界**：shop_phone/shop_name/sku_image 与 sku_name/price 同策略（不校验真实性），是存量设计模式（服务不校验商品），不是本需求新开的口子；但电话/单价进卡片后，伪造值对买家感知更强，长期应改为 create 按 sku_id 回查商品服务取图片/单价并快照（需处理商品下架/改价的快照语义），列入产品侧规划而非本需求。

### 架构对上下文划分的影响

- **schema（1）独立为功能点并置最前**：buyer_fav/shop_phone 是收藏（6）与电话图标（4.2.2）的前置；SQLite 不改旧表，先钉死「DROP 重建」口径，否则后续所有红测试在旧库上跑出幽灵失败。
- **安全（2）先于读功能且每个 DAO 测试自带隔离陷阱**：buyer_username+buyer_deleted 恒置是所有买家 SQL 的安全契约；收藏/支付/确认三个新写接口各带「他人订单零改动」断言，防止水平越权成为最大单点风险。
- **读路径（3/4）与写路径（6/7/8）分离**：列表/计数/卡片是纯读，回归为主；收藏是独立标记写，支付/确认复用状态机写路径——各自原子可测，互不阻塞，红绿循环粒度最小。
- **「归属校验 + 委托」模式统一三个新接口**：fav/pay/confirm 的先查归属性校验（404）与业务规则（400/状态机异常）分流，Controller 层保持薄，使 MockMvc 契约测试（状态码/错误体）与 Service 真实库测试（落库/事件/隔离）各司其职，延续项目「DAO 真库、Controller standalone MockMvc、表驱动参数化」的既有 TDD 风格。
- **复用重构（doPay/doConfirm）放在绿阶段而非红阶段**：先以 7.1.2/8.1.1 的买家行为测试逼出「payByBuyer 需要完整支付口径」，再把两处复制折叠为委托——TDD 意义是重构必有测试网兜底（既有 OrderBalancePayTest/OrderShipServiceTest 回归）。

### TDD 执行顺序建议

功能点 1（schema 扩展）→ 功能点 2（隔离回归，跑既有测试兜底）→ 功能点 3/4（列表/计数/卡片回归 + shopPhone/buyerFav 两字段红绿）→ 功能点 5（快照补齐写入路径）→ 功能点 6（收藏）→ 功能点 7（去支付，含 doPay 提取重构）→ 功能点 8（确认收货，含 doConfirm 提取重构）→ 功能点 9/10/11/12（操作回归）。每个任务项独立「红 → 绿 → 重构」小循环；DAO 用真实临时 SQLite + MyBatis XML、Controller 用 standalone MockMvc + Mockito、隔离断言用 verify(only())、表驱动用 @ParameterizedTest + @MethodSource；红阶段确保零误过。