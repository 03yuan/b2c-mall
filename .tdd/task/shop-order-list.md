# 店铺订单管理（商家订单查询工作台 + 买家订单列表）TDD 拆解文档

## 需求概述

来源 `shop_order_list.docx`，实际包含两个页面：①**商家端订单查询工作台**——登录商家访问、只看当前登录店铺数据，顶部 11 维筛选（订单编号精确、订单类型、订单类别、订单状态、收件人姓名模糊、联系电话模糊、支付方式、售后状态、物流类型、门店名称、下单时间区间）+ 查询/导出/重置/批量发货 + 订单状态快捷标签栏（全部/待付款/待发货/待收货/交易成功/交易失败/待成团）+ 订单行（头部、商品信息带标签、成交单价/数量、实付金额共 N 件、支付方式、买家/收货人、订单状态、售后状态、操作列：查看详情/联系买家/发货/退款信息）；②**买家端订单列表**——状态 Tab（全部/待付款/待发货/待收货/已完成，括号内数量）、订单编号精确查询、订单卡片（订单编号、店铺名称、商品行、总额、状态、支付方式）、操作（订单详情/立即购买/删除订单-逻辑删除）。

**架构背景**：Maven 多模块微服务（Gateway 8081 → order-service，订单数据归 order.db SQLite，schema 变更须先 DROP 旧表重建）。分层统一：`web`（Controller + Request/VO + GlobalExceptionHandler）→ `service` → `dao/mapper` + `dao/po`。设计模式现状：支付策略模式（ALIPAY/WECHAT 模拟实现）、订单状态模式（PENDING→PAID→SHIPPED→COMPLETED，OrderStateFactory 从 DB 值恢复状态对象）、RocketMQ topic `b2c-order-events`（tag：PAY_SUCCESS/REFUND_APPLY/CONFIRM_RECEIPT）。售后单据表 tb_refund（PROCESSING/DONE）已存在。**现状债**：order-service TokenFilter 当前为透传空实现、OrderController 硬编码 shopId=1/operatorId=1；tb_order.buyer_username 下单时未写入（恒 null）；user-service 登录为内存桩不写 Redis token；order pom 无 EasyExcel（product 已引入 3.3.4）。

**已确认的决策**（2026-10-26 交互确认）：
- 拆解范围含两个页面：商家端工作台 + 买家端订单列表
- 状态机扩展：新增 `FAILED`（交易失败/已取消）、`GROUP_PENDING`（待成团）枚举与状态类；GROUP_PENDING 仅枚举与筛选支持、无流转入口（拼团功能未落地）；FAILED 的入口为「买家取消待付款订单」
- 发货扩展物流信息：tb_order 新增 `logistics_no`/`logistics_type`，ship 接口接收物流单号+物流类型（普通快递/同城配送），批量发货复用同一逻辑
- 联系买家：后端提供买家联系信息接口（买家账号+脱敏手机号），唤起聊天窗口为前端职责

**默认决策**（交互超时，按推荐方案推进，如有出入请指正）：
- 维持一单一商品模型：VO 商品行用**数组结构**（当前恒 1 条）向前兼容，共 N 件=Σitems.quantity 当前等于 quantity；多商品主从表（tb_order_item）拆分列入重构方向，本期不动下单流程
- 补齐买家 token 体系：user-service 登录成功写 Redis `b2c:token:{token}`（载荷 userId/username，24h TTL）；order-service 买家接口从 token 解析 username 过滤
- 下单接口接入买家 token：`buyer_username` 从登录上下文写入；shopId 沿用现状硬编码 1（商品→店铺归属解析列入重构方向）
- 列表展示手机号一律**脱敏**；导出 Excel 为**明文**（商家履约需要），口径差异显式固化
- 删除订单=买家侧逻辑删除（`buyer_deleted=1`），后台保留数据；买家列表/计数/详情恒排除
- 下单时间快捷选项（今日/昨日/近 7 天/近 30 天）为前端换算职责，后端只接收起止日期区间（含首尾，按天）

**字段映射**（需求列 → tb_order 字段）：

| 需求列 | 字段 | 现状 |
|---|---|---|
| 订单编号 | `order_no` | 已有 |
| 下单时间 | `create_time` | 已有 |
| 订单类型（业务类型：普通/秒杀） | `order_type` | **新增**（默认 NORMAL） |
| 订单类别（普通订单/电子卡券订单） | `order_category` | **新增**（默认 NORMAL，CARD=电子卡券） |
| 订单状态（含交易失败/待成团） | `status`（扩 FAILED/GROUP_PENDING） | 已有扩展 |
| 收件人姓名 | `receiver_name` | 已有 |
| 联系电话（展示脱敏/导出明文） | `receiver_phone` | 已有 |
| 支付方式（余额/微信/支付宝） | `pay_type`（扩 BALANCE） | 已有扩展 |
| 售后状态（暂无售后/退款申请中/退款完成） | 关联 `tb_refund.status` 派生 | 已有表 |
| 物流类型（普通快递/同城配送） | `logistics_type` | **新增** |
| 物流单号 | `logistics_no` | **新增** |
| 门店名称（多门店模式） | `store_name` | **新增**（可空） |
| 店铺名称（买家卡片） | `shop_name` | **新增**（快照） |
| 商品图片 | `sku_image` | **新增**（快照） |
| 成交单价 | `unit_price` | **新增**（当前仅有 total_price） |
| 实付金额（共 N 件） | `total_price`（N=Σquantity） | 已有 |
| 买家账号 | `buyer_username` | 已有（下单补写入） |
| 赠品标记 | `is_gift` | **新增**（默认 0） |
| 买家删除标记 | `buyer_deleted` | **新增**（默认 0） |

---

## 功能点 1：tb_order 数据模型扩展（schema 变更先行）

### 功能上下文 1.1：DAO/PO 层 - 表结构与实体

- 任务项 1.1.1：`order.sql` 的 tb_order 新增列 order_type、order_category、logistics_type、logistics_no、store_name、shop_name、sku_image、unit_price、is_gift、buyer_deleted；status 注释扩展 FAILED/GROUP_PENDING；schema 变更先 `DROP TABLE IF EXISTS tb_order` 再重建（SQLite CREATE IF NOT EXISTS 不改表）
  - 测试思路：真实 SQLite 临时库执行 order.sql → 插入一条含全部新字段的订单 → 逐字段读回断言一致。Fail 边界：漏字段 → `no such column`；忘 DROP 旧表 → 新字段静默丢失

- 任务项 1.1.2：新增组合索引 `idx_order_shop_create_time (shop_id, create_time)` 支撑下单时间区间筛选
  - 测试思路：三层断言——PRAGMA index_info 索引列精确匹配（含顺序）、EXPLAIN QUERY PLAN 确认走索引、满量数据区间查询计时达标。Fail 边界：列顺序写反（create_time 在前）→ 按 shop_id 等值+时间范围时索引失效

- 任务项 1.1.3：OrderPO 同步新增对应属性（orderType、orderCategory、logisticsType、logisticsNo、storeName、shopName、skuImage、unitPrice、isGift、buyerDeleted）
  - 测试思路：insert 后 selectById 断言 PO 各属性非 null 且值正确；unitPrice 用 BigDecimal.compareTo 断言。Fail 边界：PO 漏属性导致 MyBatis 映射丢列

- 任务项 1.1.4：默认值约束：order_type='NORMAL'、order_category='NORMAL'、is_gift=0、buyer_deleted=0（未显式传入时）
  - 测试思路：按现有 insert 列清单（不带新字段）插入 → 读回断言为默认值而非 null。Fail 边界：默认值缺失 → 列表/筛选项 null 与「暂无数据」混淆

## 功能点 2：登录态与数据隔离（商家 shopId / 买家 username 双身份）

### 功能上下文 2.1：user-service - 买家 token 落 Redis

- 任务项 2.1.1：登录成功后写 Redis `b2c:token:{token}`，载荷含 userId、username，TTL 24 小时（与 employee/shop 登录同一前缀、同一 TTL 约定）
  - 测试思路：mock StringRedisTemplate 断言 opsForValue().set 以 `b2c:token:` 前缀 key、含 username 的 JSON 载荷、24h TTL 被调用。Fail 边界：不写 Redis → 买家接口全线 401

- 任务项 2.1.2：登录失败（密码错误统一消息、账号禁用）不写 token
  - 测试思路：表驱动两类失败 → 断言 Redis 无任何写入。Fail 边界：失败也发 token → 禁用账号可登买家端

### 功能上下文 2.2：order-service Filter/组件层 - TokenFilter 重新启用（双身份解析）

- 任务项 2.2.1：参照 product TokenFilter 实现真实校验：Bearer 解析 + Redis `b2c:token:{token}` 校验 + swagger 白名单；载荷含 employeeId/shopId → 商家身份写入 RequestHolderComponent；含 userId → 买家身份（username）写入
  - 测试思路：无 token/无效 token → 401「未登录或 token 缺失/无效」；商家 token → RequestHolderComponent.get().getShopId() 与 Redis 一致；买家 token → username 一致。Fail 边界：保留透传实现 → 任何人可看全部订单

- 任务项 2.2.2：商家接口要求商家身份（shopId 非空），买家 token 访问商家接口 → 401；买家接口要求买家身份（username 非空），商家 token 访问买家接口 → 401
  - 测试思路：买家 token 调 GET /order/page → 401；商家 token 调 GET /order/buyer/page → 401。Fail 边界：身份混用 → 买家看到店铺全部订单/商家接口 shopId 为 null 查出全平台数据

- 任务项 2.2.3：下单接口从买家 token 写入 buyer_username（POST /order/create 要求买家登录态）
  - 测试思路：带买家 token create → DB 中 buyer_username=登录 username；无 token → 401。Fail 边界：buyer_username 仍为 null → 买家列表永远查不到自己的单

### 功能上下文 2.3：Service/DAO 层 - 强制隔离

- 任务项 2.3.1：商家侧所有查询/更新 SQL 恒带 `shop_id = 登录 shopId`，请求参数中的 shopId 一律忽略
  - 测试思路：MockMvc 带 `?shopId=2` 调用 → Mockito `verify(only())` 断言 Service 仅以登录 shopId 被调用。Fail 边界：信任前端 shopId → 水平越权

- 任务项 2.3.2：买家侧所有查询/更新 SQL 恒带 `buyer_username = 登录 username AND buyer_deleted = 0`，请求参数中的 buyerUsername 一律忽略
  - 测试思路：真实临时库插入买家 A、B 订单各若干 + A 的 1 条已删订单 → A 登录查询断言不含 B 订单、不含已删订单。Fail 边界：漏 buyer_deleted 条件 → 已删除订单复活

- 任务项 2.3.3：跨身份 id 防护：发货/详情/取消/删除传入他店（他人）orderId → 不生效（404 或零改动），目标数据原样
  - 测试思路：店铺 A 登录对店铺 B 订单发货 → B 订单 status 不变；买家 A 取消买家 B 订单 → 404。Fail 边界：UPDATE/DELETE 漏身份条件 → 改到他人数据

## 功能点 3：订单状态机扩展（FAILED/GROUP_PENDING + 取消事件）

### 功能上下文 3.1：statemachine 层 - 枚举、状态类、工厂

- 任务项 3.1.1：OrderStatusEnum 新增 FAILED（交易失败/已取消）、GROUP_PENDING（待成团）；新增 FailedState 终态类（pay/ship/confirm/cancel 全部抛 IllegalStateException）、GroupPendingState（拼团未落地，暂无可达流转，全部抛异常）；OrderStateFactory 补充两个 DB 值映射
  - 测试思路：表驱动断言工厂对 FAILED/GROUP_PENDING 返回正确状态类；终态各操作逐一断言抛 IllegalStateException 且消息明确。Fail 边界：工厂漏映射 → 含新状态的旧数据恢复状态对象时空指针 500

- 任务项 3.1.2：OrderState 接口新增 `cancel()`；PendingState.cancel() → FailedState；PaidState/ShippedState/CompletedState.cancel() 抛 IllegalStateException（已付款需走退款而非取消）
  - 测试思路：PENDING 执行 cancel → 返回 FailedState 实例；PAID/SHIPPED/COMPLETED cancel → 逐一断言抛异常。Fail 边界：任意状态可取消 → 已付款订单被绕过退款直接取消

### 功能上下文 3.2：Service 层 - 取消流转落地

- 任务项 3.2.1：cancel(orderId, operatorId)：PENDING→FAILED，写 tb_order_log（event=CANCEL，from=PENDING，to=FAILED，含操作人）
  - 测试思路：取消后断言 DB status=FAILED 且日志行 from/to/event 正确；对 FAILED 订单重复取消 → 抛异常（终态幂等防护）。Fail 边界：状态变了日志缺失 → 订单流转审计断链

## 功能点 4：支付方式扩展 - 余额支付 BALANCE

### 功能上下文 4.1：Service 层 - 策略扩展

- 任务项 4.1.1：PayTypeEnum 新增 BALANCE；新增 BalanceStrategyService（模拟实现返回 true，getPayType=BALANCE，与 WechatStrategyService 同风格）
  - 测试思路：pay(orderId, "BALANCE") → 状态 PENDING→PAID 且 DB pay_type=BALANCE、pay_time 非空；payType 传非法值 → 400「不支持的支付方式」。Fail 边界：策略 Bean 未注册 → 余额支付报「不支持的支付方式」

- 任务项 4.1.2：回归保障：BALANCE 支付成功仍发布 PAY_SUCCESS 事件（销量回写链路不受影响）
  - 测试思路：mock OrderEventPublisher → verify publishPaySuccess 被调用且载荷含 skuId/quantity。Fail 边界：新策略绕过事件发布 → 商品销量不回写

## 功能点 5：商家端订单列表分页 + 多维度筛选 + 状态快捷标签

### 功能上下文 5.1：DAO 层（OrderMapper + XML 动态 SQL）

- 任务项 5.1.1：动态条件分页查询：shop_id 固定；order_no 精确匹配；receiver_name、receiver_phone LIKE 模糊（%%）；order_type、order_category、status、pay_type、logistics_type、store_name 等值匹配；create_time 起止区间含首尾（start 日 00:00:00 ≤ t ≤ end 日 23:59:59）
  - 测试思路：真实 SQLite 临时库固定数据（约 10 条本店覆盖各维度 + 1 条他店泄漏陷阱），逐维度单独断言命中子集；每个筛选用例**双断言**（selectPage 命中数与 countByFilter 一致）。Fail 边界：LIKE 不带 % 变精确匹配；区间右边界写成 `< end` → 尾日订单整天丢失

- 任务项 5.1.2：售后状态筛选：暂无售后=NOT EXISTS(tb_refund 该订单任意行)；退款申请中=EXISTS(status='PROCESSING')；退款完成=EXISTS(status='DONE')
  - 测试思路：3 笔订单分别无退款单/PROCESSING/DONE → 三种筛选各命中且仅命中 1 笔。Fail 边界：写成 JOIN 未去重 → 一单多条退款单时列表行重复

- 任务项 5.1.3：状态快捷标签 = status 等值筛选的预设（待付款=PENDING/待发货=PAID/待收货=SHIPPED/交易成功=COMPLETED/交易失败=FAILED/待成团=GROUP_PENDING）；「全部」不传 status 条件
  - 测试思路：点击标签等价于带对应 status 参数查询，各标签断言命中集合；「全部」断言 6 种状态订单都返回。Fail 边界：「全部」仍残留默认状态条件 → 交易失败订单永远无法被看到

- 任务项 5.1.4：排序与分页边界：create_time DESC、同值按 id DESC 稳定排序；page≥1、size 限 1~100；超页返回空列表；total=筛选命中总数
  - 测试思路：插入相同 create_time 的 3 条断言返回顺序稳定、翻页不重复不漏行；10 条数据 size=3 → total=10、第 4 页为空；size=0/负数 → IllegalArgumentException 而非 SQL 500。Fail 边界：默认按 id 排 → 下单时间排序失效

### 功能上下文 5.2：Service/Controller 层（GET /order/page）

- 任务项 5.2.1：Controller 参数校验：非法 page/size → 400；下单时间 start > end → 400「开始日期不能晚于结束日期」；筛选条件透传且 shopId 强制取自登录上下文
  - 测试思路：standalone MockMvc + Mockito：表驱动非法参数各断言 400 且中文消息、Service 零调用；合法请求 verify(only()) 断言 loginShopId。Fail 边界：start>end 静默查空而非报错 → 商家误判无单

- 任务项 5.2.2：空结果返回空列表 + total=0（前端渲染「暂无数据」），任何字段不伪造数值
  - 测试思路：无匹配筛选 → 断言 list 为空集合、total=0。Fail 边界：填充 0/示例数据（沿用既有「暂无数据」约定）

## 功能点 6：订单行数据组装（VO：标签派生/手机号脱敏/共 N 件/售后状态）

### 功能上下文 6.1：Service 层 - 行 VO 组装

- 任务项 6.1.1：行 VO 字段齐全：orderId、orderNo、createTime、storeName（多门店订单非空）、items[{skuImage、skuName、unitPrice、quantity、isGift}]、totalPrice（实付金额）、totalQuantity（共 N 件=Σitems.quantity，当前模型=quantity）、payType、buyerUsername、receiverName、receiverPhoneMasked、status、refundStatus
  - 测试思路：断言 VO 各列齐全、items 为数组且含 1 条、totalQuantity=quantity。Fail 边界：漏列（售后状态/单价）→ 前端表头对不上；items 返回对象而非数组 → 多商品扩展时前端重写

- 任务项 6.1.2：商品标签派生：status=PAID →「待发货」、SHIPPED →「待收货」、refundStatus=PROCESSING →「退款申请中」、order_category=CARD →「电子卡券订单」、logistics_type=CITY →「同城配送」、is_gift=1 →「赠品」
  - 测试思路：表驱动 6 种标签各断言命中与不命中两种情形。Fail 边界：标签硬编码不随字段变化 → 状态流转后标签残留

- 任务项 6.1.3：手机号脱敏：11 位 → 前 3 后 4（138****5678）；非 11 位 → 中间掩码；null/空 → 原样返回空
  - 测试思路：表驱动覆盖 11 位/7 位/空串/null 边界。Fail 边界：列表明文手机号 → 隐私泄漏；脱敏工具对短号串越界抛异常

- 任务项 6.1.4：售后状态派生用批量查询：按本页 orderId IN 查 tb_refund 一次取回，禁止逐行查询（N+1）
  - 测试思路：mock RefundMapper 断言 20 行页面仅 1 次批量查询调用。Fail 边界：每行一次查询 → 20 行 21 次 SQL

### 功能上下文 6.2：操作列展示条件（后端供判定字段，前端渲染）

- 任务项 6.2.1：VO 同时提供 status、refundStatus **原始枚举值**（非仅中文文案），前端据此控制「发货」（仅待发货）、「退款信息」（仅有售后）按钮显隐
  - 测试思路：断言 VO 含原始 status/refundStatus 字段且取值为枚举名。Fail 边界：只给中文文案 → 前端用文案判逻辑，文案一改按钮失效

## 功能点 7：发货（单条含物流）与批量发货

### 功能上下文 7.1：Service 层 - 单条发货（扩展现有 ship）

- 任务项 7.1.1：ship 扩展为 ship(orderId, logisticsNo, logisticsType, operatorId)：仅 PAID 可发货（状态机保障），成功时写入 logistics_no/logistics_type、状态流转 SHIPPED、写 tb_order_log（event=SHIP）
  - 测试思路：PAID + 物流单号 → 断言 SHIPPED 且物流字段落库、日志正确；PENDING 发货 → IllegalStateException。Fail 边界：状态流转了但物流单号不落库 → 详情页无物流信息

- 任务项 7.1.2：参数校验：logisticsNo 非空、logisticsType 在枚举内（EXPRESS 普通快递/CITY 同城配送）→ 违反 400；shopId 强制隔离（他店订单不生效）
  - 测试思路：空单号/非法类型 → 400 且 DB 零改动；他店 orderId → 状态不变。Fail 边界：空单号发货成功 → 商家无单号履约

### 功能上下文 7.2：Controller + Service - 批量发货（POST /order/batch-ship）

- 任务项 7.2.1：请求体 items[{orderId, logisticsNo, logisticsType}] 逐条独立处理：成功逐条流转；非待发货/他店/不存在的订单记入失败列表（orderId+原因），好单照常发货不连坐
  - 测试思路：3 条混合（正常 PAID/已 SHIPPED/他店）→ 断言 success=1、failed=2 且各带中文原因，DB 仅正常单变更。Fail 边界：一条失败整批回滚 → 批量操作不可用

- 任务项 7.2.2：入参校验：items 为空 → 400；单条缺物流单号 → 该条计入失败、其余照常
  - 测试思路：表驱动非法输入断言 400/部分失败。Fail 边界：空 items 返回 200 成功 → 前端误以为发货完成

## 功能点 8：导出订单 Excel

### 功能上下文 8.1：Service 层 - 导出数据组装（复用列表筛选）

- 任务项 8.1.1：导出与列表共用同一筛选构建器：按当前筛选条件取全量（不分页），排序与列表一致（create_time DESC, id DESC）
  - 测试思路：真实临时库造超默认页大小数据，带筛选导出 → 断言行数=该条件命中总数、排序一致、不含条件外订单。Fail 边界：忽略筛选导出全店数据

### 功能上下文 8.2：web 层 - Excel 下载（GET /order/export，EasyExcel）

- 任务项 8.2.1：order pom 引入 EasyExcel 3.3.4（对齐 product 模块）；新增 OrderExcelColumns 列定义常量类作为导出表头单一来源
  - 测试思路：反射断言导出 VO 的 @ExcelProperty 表头与 OrderExcelColumns 逐列一致。Fail 边界：表头两处硬编码 → 后续改列漂移

- 任务项 8.2.2：响应头 Content-Type=xlsx、Content-Disposition=attachment 且文件名含时间戳（UTF-8 编码）；空结果导出仅表头的有效 xlsx；手机号导出口径=明文（商家履约需要，与列表脱敏差异显式固化在列定义注释）
  - 测试思路：MockMvc 断言响应头；读回 Excel 断言首行表头与列定义一致、数据行手机号为明文 11 位；无匹配筛选 → 仅表头无数据行。Fail 边界：空结果 500；导出被误脱敏 → 商家无法联系收件人

## 功能点 9：商家端单订单操作（详情/联系买家/退款信息）

### 功能上下文 9.1：Controller + Service - 订单详情（增强 GET /order/{orderId}）

- 任务项 9.1.1：DetailVO 返回完整明细：行 VO 全字段 + receiverAddress、logistics_no/logistics_type、pay_time + 状态流转日志列表（tb_order_log 按时间升序）；shopId 隔离，他店/不存在 → 404
  - 测试思路：断言 DetailVO 字段齐全、含日志轨迹（PENDING→PAID→SHIPPED 各节点）；他店 orderId → 404。Fail 边界：详情缺物流/支付时间 → 商家履约核对残缺；越权读详情

### 功能上下文 9.2：Controller + Service - 联系买家（GET /order/{orderId}/buyer-contact）

- 任务项 9.2.1：返回 {buyerUsername, maskedPhone}，供前端唤起聊天窗口（唤起为前端职责）；shopId 隔离
  - 测试思路：断言 buyerUsername 正确、maskedPhone 为脱敏格式；他店 orderId → 404。Fail 边界：返回明文手机号 → 隐私泄漏；buyerUsername 为 null（历史单未写入）→ 需断言返回空串而非 500

### 功能上下文 9.3：Controller + Service - 退款信息（GET /order/{orderId}/refund）

- 任务项 9.3.1：存在 tb_refund 记录 → 返回 status/amount/create_time；无记录 → 返回「暂无售后」语义（空对象/标识位），不报错
  - 测试思路：有/无退款单两路断言；多条退款单取最新一条。Fail 边界：无退款单返回 404/500 → 前端无法区分「无售后」与「接口异常」

## 功能点 10：买家端订单列表（状态 Tab 计数 + 订单卡片 + 编号查询）

### 功能上下文 10.1：DAO 层 - 买家维度查询

- 任务项 10.1.1：买家分页查询：buyer_username=登录恒置、buyer_deleted=0 恒置；status 可选等值；order_no 精确匹配；排序 create_time DESC, id DESC；分页边界同商家端
  - 测试思路：真实临时库（买家 A 6 条含 1 条已删 + 买家 B 2 条陷阱）→ 各筛选双断言（page+count）；已删订单任何筛选下不出现。Fail 边界：漏 buyer_deleted → 已删订单复活；漏 buyer_username → 看到他人订单

- 任务项 10.1.2：状态计数（GET /order/buyer/status-counts 数据源）：GROUP BY status 统计且排除 buyer_deleted=1；返回结构固定含 全部/待付款/待发货/待收货/已完成 五个 key，无单状态返回 0 而非缺失
  - 测试思路：买家 A 数据断言各 key 值正确（对应示例：待付款 0/待发货 9 类形态）、已删订单不计入；全部=其余各状态之和（含 FAILED/GROUP_PENDING）。Fail 边界：key 缺失 → 前端括号显示 undefined；已删订单计入 → 计数与列表不一致

### 功能上下文 10.2：Service/Controller 层（GET /order/buyer/page、GET /order/buyer/status-counts）

- 任务项 10.2.1：卡片 VO 组装：orderNo、shopName、createTime、status（原始枚举+文案）、payType（待发货卡片附带展示如「余额支付」）、items[{skuImage、skuName、quantity、unitPrice}]、totalPrice（同一订单多商品合并总额，当前模型=单行 total_price）、skuId（立即购买跳转用）
  - 测试思路：断言卡片字段齐全；待发货卡片含 payType、其他状态卡片不含；items 为数组。Fail 边界：缺 skuId → 立即购买无法跳转；缺 shopName → 卡片头部空白

- 任务项 10.2.2：买家身份强制：username 取自登录上下文，参数 buyerUsername 忽略；商家 token（无 userId 载荷）访问 → 401
  - 测试思路：MockMvc + Mockito verify(only()) 断言 loginUsername；商家 token → 401。Fail 边界：身份混用 → 越权

## 功能点 11：买家端操作（详情/删除/取消/立即购买）

### 功能上下文 11.1：Controller + Service - 买家单订单操作

- 任务项 11.1.1：买家订单详情：复用 DetailVO 组装，buyer_username 隔离，他人订单/不存在 → 404
  - 测试思路：买家 A 读买家 B 订单 → 404；本人订单 → 字段齐全（含物流、支付记录、状态文案）。Fail 边界：越权读他人订单

- 任务项 11.1.2：删除订单=逻辑删除：UPDATE tb_order SET buyer_deleted=1 WHERE id=? AND buyer_username=登录 AND buyer_deleted=0；后台记录保留；重复删除幂等成功
  - 测试思路：删除后买家列表/计数不含该单、DB 行仍在且 buyer_deleted=1、商家端列表仍可见该单；重复删除 → 200 幂等；删除他人订单 → 404 且零改动。Fail 边界：物理删除 → 商家端历史订单丢失

- 任务项 11.1.3：取消订单（POST /order/buyer/cancel/{orderId}）：复用状态机 cancel（功能点 3），仅 PENDING 可取消，buyer_username 隔离，写 event=CANCEL 日志
  - 测试思路：PENDING → FAILED 且日志正确；PAID 取消 → 400 级错误（提示已付款需申请退款）；他人订单 → 404。Fail 边界：已付款被直接取消 → 绕过退款流程资金失控

- 任务项 11.1.4：立即购买：前端跳转商品下单页，后端无新接口，依赖卡片 VO 含 skuId（任务项 10.2.1 覆盖）
  - 测试思路：见 10.2.1（断言 skuId 存在），本项不单独设红测试

---

## 架构与重构思考

### 组件间关系分析

```
商家浏览器 ─┐
           ├→ Gateway 8081（AuthFilter 验 Redis token）→ order-service（order.db）
买家浏览器 ─┘    ├─ TokenFilter（重新启用：employee 载荷→shopId 商家身份 / user 载荷→username 买家身份）
                ├─ OrderQueryController（商家：GET /order/page、GET /order/export、POST /order/batch-ship、
                │     GET /order/{id}/buyer-contact、GET /order/{id}/refund）
                ├─ OrderController（已有 create/pay/ship/confirm/{id}；ship 扩展物流、create 写 buyer_username）
                ├─ BuyerOrderController（买家：GET /order/buyer/page、/status-counts、/cancel、/delete、详情）
                ├─ OrderService → OrderMapper（动态 SQL）/ OrderLogMapper / RefundMapper（批量派生售后状态）
                ├─ 状态机（+FailedState / +GroupPendingState，OrderState +cancel()）
                └─ 支付策略（+BalanceStrategyService）
user-service：login 成功写 Redis b2c:token:{token}（买家身份源头）
```

关键耦合点与重构方向：

1. **TokenFilter 双身份解析是权宜之计**：靠载荷字段存在性（employeeId vs userId）区分身份，语义脆弱。长期应在 token 载荷加显式 `identityType` 字段，或拆分商家/C 端网关路由域；且 TokenFilter 已在 product/shop/order 三处复制，测试全绿后可沉淀到 common 模块统一维护。
2. **一单一商品模型是最大架构债**：tb_order 的 order_no UNIQUE + 单 sku_id 与买家端「多商品合并总额」的目标形态不符。本期 VO items 数组已向前兼容；多商品落地时需拆 tb_order + tb_order_item 主从表、调整 order_no 唯一约束、改造下单接口，届时总价/共 N 件/导出口径全部重估——本需求内不预判实现。
3. **shop_name/store_name 采用快照冗余**（下单写入），店铺改名不回溯历史订单（与 sku_name 冗余同策略）；若要求实时一致需 order→shop Feign 实时查，列表页 N+1 风险大，维持快照。
4. **售后状态派生耦合 tb_refund**：EXISTS 子查询在退款单量大后需补 `(order_id, status)` 复合索引；若售后状态种类膨胀（换货/退货退款），考虑 tb_order 冗余 aftersale_status 由售后流程回写，列表免派生。
5. **存量硬编码 shopId=1 的债**：create/pay/confirm 仍用 DEFAULT_SHOP_ID/operatorId，本需求仅新增查询/发货/买家接口接入登录上下文；存量接口迁移需网关与前端联调，单独立项，不在本需求内混入。
6. **GROUP_PENDING 是不可达状态类**（全部流转抛异常）：拼团落地时补充流转路径（如 GROUP_PENDING→PAID 成团、→FAILED 流团），测试先行覆盖工厂分发边界，防止未来上线即空指针。
7. **导出明文 vs 列表脱敏的口径差异**：已显式决策并固化在 OrderExcelColumns 注释，防止后续「统一脱敏」好意误改导出导致商家无法履约。
8. **买家计数与列表筛选必须同源**：buyer_deleted=0 等条件共用同一 filter 构建器，沿用 product countByFilter 双断言模式防漂移（计数与列表不一致是此类页面最高发缺陷）。

### 架构对上下文划分的影响

- **schema 变更独立成功能点 1 并置于最前**：所有后续 DAO/Service 测试依赖新字段，且 SQLite 不支持 ALTER 加约束，必须先钉死表结构；索引（1.1.2）与字段（1.1.1）拆为独立任务，三层断言法（列精确匹配/EXPLAIN/计时）只压索引任务。
- **鉴权（功能点 2）先于一切数据功能**：user-service 写 token 是买家功能的源头，必须最先通；shopId/username 强制隔离是每条 SQL 的安全契约，后续每个 DAO 测试都带隔离断言与陷阱数据（沿用商品列表同一原则）。
- **写路径（状态机 3/支付 4/发货 7）与读路径（列表 5/6、买家 10）分开**：状态机与策略是既有模式的增量扩展，独立红绿小循环；列表是纯读，互不阻塞。
- **DAO 层（5.1/10.1）与 VO 组装（6/10.2）分层**：动态筛选 SQL 最易错，用真实 SQLite 临时库验证命中集合与双断言；标签派生/脱敏/共 N 件用 Mockito 表驱动快速穷举边界（沿用 product 模块既有 TDD 风格）。
- **导出按「数据组装」与「文件格式」分层**：EasyExcel 是外部依赖，列定义收敛为单一来源后 IO 层薄薄一层，核心断言（筛选复用、明文凭据、空结果）压在 Service 与响应头。
- **买家端（10/11）复用商家端奠定的 schema/状态机/脱敏工具，但独立成功能点**：身份维度不同（buyer_username + buyer_deleted），防止商家端测试惯性导致买家维度条件遗漏。
- **批量发货（7.2）与单条发货（7.1）分开**：批量是逐条独立处理+失败汇总的编排逻辑，单条是状态机流转+物流落库，各自原子可测，mock 与真实库分离。

### TDD 执行顺序建议

功能点 1（schema）→ 功能点 2（登录态与隔离，安全先行）→ 功能点 3（状态机）→ 功能点 4（余额支付）→ 功能点 5（商家列表+筛选）→ 功能点 6（行 VO 组装）→ 功能点 7（发货/批量发货）→ 功能点 8（导出）→ 功能点 9（商家单订单操作）→ 功能点 10（买家列表+计数）→ 功能点 11（买家操作）。每个任务项独立「红 → 绿 → 重构」小循环；红阶段确保零误过，DAO 用真实临时 SQLite 库、Controller 用 standalone MockMvc + Mockito、表驱动 @ParameterizedTest + @MethodSource。
