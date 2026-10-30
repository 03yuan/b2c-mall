# 店铺 Dashboard（商家数据概览）TDD 拆解文档

## 需求概述

商家管理后台首页（数据概览看板），面向店铺运营/商家用户，集中展示：①顶部 6 个订单状态统计卡片；②今日 vs 昨日 24 小时交易金额趋势折线图；③4 项核心经营指标（含同比昨日变化率）；④消息通知滚动列表。登录商家账号才可访问，且只查看当前登录店铺的数据。来源：`shop_dashboard.docx`（项目需求 v1.1）。

**架构背景**：Maven 多模块微服务。请求经 Spring Cloud Gateway（8081）→ shop-service 聚合。数据分散在各服务私有 SQLite 库（严禁跨库互查）：订单数据归 order-service（order.db）、库存归 product-service（product.db）、消息归 shop-service（shop.db）。shop-service 通过 OpenFeign 查询 order/product 服务的统计接口完成聚合；消息通知走 RocketMQ 事件（47.100.22.158:9876）异步落入 shop-service 消息表。Redis 仅用于登录 token（`b2c:token:`）。分层统一：`web`（Controller + VO + 全局异常处理）→ `service` → `dao/mapper` + `dao/po`。

**已确认的决策**：
- 文档中的数值（0/12/2/24/1/3、+100.00%）是示例，不是种子数据；全部从数据库真实统计；无数据时显示「暂无数据」（接口层返回 null，前端渲染文案），不显示伪造数字
- Dashboard 聚合接口归属 shop-service（OpenFeign 调 order/product 的统计接口）
- 消息来源：order-service 在支付成功/退款申请/确认收货时发 RocketMQ 事件，shop-service 消费并持久化消息表；不从订单表实时推导
- 库存预警阈值为商品级可配置字段（tb_sku 新增 stock_threshold），统计 sku 库存 < 自身阈值的商品数
- 同比公式：(今日值 - 昨日值) ÷ 昨日值 × 100%，昨日无数据（含昨日值为 0）时显示 100%（需求文档规定，优先于「暂无数据」）
- 时间口径：Asia/Shanghai 自然日（今日 00:00 ~ 当前时刻）

**订单状态映射**（订单状态机 PENDING→PAID→SHIPPED→COMPLETED）：
待付款=PENDING；待发货=PAID；待收货=SHIPPED；已完成=COMPLETED；退款/售后=进行中的退款申请单据数；库存预警=库存低于自身阈值的商品数。

---

## 功能点 1：登录商家只看到自己店铺的数据（权限与隔离）

### 功能上下文 1.1：网关层（gateway）与登录态传递

- 任务项 1.1.1：`/shop/dashboard/**` 路由指向 shop-service，且不在鉴权白名单（必须携带 token）
  - 测试思路：端到端：不带 Authorization 头请求 dashboard 接口 → 401。Fail 边界：误加白名单则未登录可看经营数据

- 任务项 1.1.2：token 中携带 shopId，shop-service 从当前登录态（token 解析/请求头透传）获取 shopId，不接受请求参数指定 shopId
  - 测试思路：请求带 `?shopId=2`（与登录店铺不同）→ 返回的仍是登录店铺数据，参数被忽略。Fail 边界：信任客户端传入的 shopId 即产生水平越权

### 功能上下文 1.2：shop-service Controller 层（DashboardController）

- 任务项 1.2.1：DashboardController 提供统一入口 `GET /shop/dashboard/summary`（一次返回卡片+趋势+指标），shopId 由登录上下文注入
  - 测试思路：MockMvc 携带 shopId=1 的登录上下文调用 → 200 且 VO 各区块齐全。Fail 边界：未实现时 404

- 任务项 1.2.2：数据隔离——聚合查询只查当前 shopId 的数据
  - 测试思路：mock Feign 返回不同店铺数据，断言传给下游统计接口的参数恒为登录 shopId；端到端：店铺 A 登录 → 所有数值只来自店铺 A。Fail 边界：忘记按 shopId 过滤则 A 看到全站数据

---

## 功能点 2：顶部 6 个订单状态卡片显示真实统计

### 功能上下文 2.1：order-service 数据访问层（dao/mapper/OrderMapper + OrderMapper.xml）

- 任务项 2.1.1：按 shopId 分状态计数 SQL：待付款/待发货/待收货/已完成四个状态各自的订单总数
  - 测试思路：真实 SQLite 测试库按四种状态各插入 N 条订单 → 断言四个计数与插入数一致。Fail 边界：状态过滤条件写错（如把 PAID 算进待付款）时计数串位

- 任务项 2.1.2：退款/售后计数 SQL：进行中的退款申请单据总数（shopId 维度）
  - 测试思路：测试库插入 2 条进行中 + 1 条已完成退款申请 → 断言计数为 2。Fail 边界：不按「进行中」过滤把历史退款也算入

- 任务项 2.1.3：订单查询统一带 shopId 条件（不可跨店）
  - 测试思路：两个店铺各插入 3 条订单 → 各自计数均为 3 而非 6。Fail 边界：漏 shopId 条件

### 功能上下文 2.2：product-service（库存预警统计）

- 任务项 2.2.1：tb_sku 新增 stock_threshold 字段（商品级预警阈值），schema 变更需先删旧表重建（SQLite CREATE IF NOT EXISTS 不改表）
  - 测试思路：真实库插入带 threshold 的 sku → 字段可读回。Fail 边界：建表脚本漏字段后 SQL 报 no such column

- 任务项 2.2.2：库存预警计数：`count(*) where stock < stock_threshold`（shopId 维度）
  - 测试思路：插入 3 个 sku（2 个库存低于自身阈值、1 个高于）→ 断言计数 2。Fail 边界：条件写成 `stock <= threshold` 或用全局阈值会错

### 功能上下文 2.3：shop-service 聚合层（Feign 客户端 + Service）

- 任务项 2.3.1：OrderStatsClient / ProductStatsClient 两个 Feign 客户端，按 shopId 拉取计数
  - 测试思路：mock 下游 HTTP（WireMock 或 mock bean）→ 断言请求路径与 shopId 参数正确。Fail 边界：Feign URL 配错 500

- 任务项 2.3.2：DashboardService 组装 6 个卡片数值（待付款/待发货/待收货/已完成/退款售后/库存预警），无数据时对应值为 null（前端显示「暂无数据」）
  - 测试思路：mock Feign 全部返回 0 → 断言 VO 中 6 个值均为 null（不是 0）；有数据时为真实数字。Fail 边界：返回 0 会让前端显示伪造感数字，违背「暂无数据」约定

---

## 功能点 3：今日 vs 昨日 24 小时交易金额趋势折线图

### 功能上下文 3.1：order-service 按小时聚合（Service + DAO）

- 任务项 3.1.1：按小时聚合支付金额 SQL：今日与昨日各 24 个点（X 轴 0:00~23:00），统计口径=按支付时间归属小时，仅统计已支付订单（PAID/SHIPPED/COMPLETED）
  - 测试思路：测试库插入不同小时支付的订单 → 断言对应小时桶金额正确。Fail 边界：按创建时间而非支付时间聚合；或把未支付订单金额计入

- 任务项 3.1.2：缺失小时补 0（曲线连续），全天无支付则整个区块为 null（前端「暂无数据」）
  - 测试思路：只有 3 个点有支付 → 断言序列长度 24、其余位置为 0；全天无支付 → 断言今日序列为 null。Fail 边界：序列长度不为 24 时前端 X 轴错位

### 功能上下文 3.2：shop-service 聚合 VO

- 任务项 3.2.1：趋势 VO 含 24 小时标签 + todayAmounts（蓝）+ yesterdayAmounts（绿）两组数据，供前端悬浮显示该小时数值
  - 测试思路：断言响应含 labels/today/yesterday 三组且长度 24。Fail 边界：字段名/顺序不匹配前端渲染错乱

---

## 功能点 4：4 项核心经营指标卡片（含同比昨日）

### 功能上下文 4.1：order-service 指标计算（Service）

- 任务项 4.1.1：支付客户数：今日完成付款的独立客户数（distinct 买家）；支付订单数：今日成功支付订单总量
  - 测试思路：今日 2 个客户共支付 3 单 → 断言客户数 2、订单数 3。Fail 边界：客户数不去重（错成 3）或按天边界错位

- 任务项 4.1.2：退款金额：今日退款总金额；客单价：今日总支付金额 ÷ 支付订单数
  - 测试思路：今日支付 200 元 2 单、退款 50 元 → 断言退款金额 50、客单价 100。Fail 边界：客单价分母取错（如含未支付单）；除零（0 单时）需返回 null 而非 Infinity

### 功能上下文 4.2：shop-service 同比计算（Service，纯函数）

- 任务项 4.2.1：同比变化率 = (今日值 - 昨日值) ÷ 昨日值 × 100%，格式化保留两位小数（如 +100.00%）
  - 测试思路：今日 2 昨日 1 → 断言 +100.00%；今日 1 昨日 2 → 断言 -50.00%。Fail 边界：正数不带 + 号；小数位截断错误

- 任务项 4.2.2：昨日无数据（null 或 0）→ 同比显示 100%（需求硬规定）；今日值无数据 → 整项 null（暂无数据）
  - 测试思路：昨日 null → 断言同比=100%；今日 null → 断言整项为 null。Fail 边界：除零抛异常返回 500

---

## 功能点 5：消息通知滚动列表（RocketMQ 事件流）

### 功能上下文 5.1：order-service 事件发布（事件驱动出口）

- 任务项 5.1.1：支付成功时发布 RocketMQ 事件（topic：b2c-order-events，tag：PAY_SUCCESS），载荷含 shopId、orderId、buyerUsername、时间戳
  - 测试思路：mock MQ producer，触发支付成功流程 → verify 发送了 tag=PAY_SUCCESS 且载荷含 orderId/shopId。Fail 边界：漏 shopId 则消费端无法归属店铺

- 任务项 5.1.2：退款申请（tag：REFUND_APPLY）与确认收货（tag：CONFIRM_RECEIPT）各发布对应事件
  - 测试思路：分别触发两个业务动作 → verify 各自 tag 与载荷正确。Fail 边界：三类事件 tag 混用导致消息文本错模板

### 功能上下文 5.2：shop-service 消息消费与持久化（RocketMQ Listener + tb_shop_message）

- 任务项 5.2.1：tb_shop_message 表（id、shop_id、type、content、order_id、created_at），消费者收到事件后落库
  - 测试思路：发送一条模拟事件 → 断言表中新增记录且 shopId/orderId/type 与事件一致。Fail 边界：消费失败无重试则丢消息

- 任务项 5.2.2：三种消息文本模板固定格式（含订单编号，防漂移集中定义）：
  「用户 xxx 已支付成功，订单号 xxx，请及时处理」/「有买家发起退款，订单编号 xxx，请在 7 天内处理」/「订单 xxx 用户 xxx 已经确认收货」
  - 测试思路：三种事件分别消费 → 断言 content 与模板逐字一致且订单号正确嵌入。Fail 边界：模板拼写漂移（如多空格）导致客服话术不统一

### 功能上下文 5.3：shop-service 消息查询（Controller + Service + DAO）

- 任务项 5.3.1：`GET /shop/dashboard/messages` 返回消息列表，按时间倒序（最新在最上），每条含 content、orderId、created_at、type
  - 测试思路：插入 3 条不同时间消息 → 断言返回顺序为倒序且 orderId 字段存在（前端点击跳订单详情的依据）。Fail 边界：正序排列或漏 orderId 字段

- 任务项 5.3.2：消息列表只含当前登录店铺的消息（shopId 隔离），支持滚动分页（page/size）
  - 测试思路：两个店铺各 2 条消息 → 各自只能查到自己的 2 条；翻页参数生效。Fail 边界：漏 shopId 过滤泄露他店消息

---

## 功能点 6：统计接口响应 < 1s（非功能需求）

来源：需求文档「实时数据趋势图表」「数据统计指标卡片」两模块的非功能需求栏，仅约束统计响应，消息列表不在此列。

### 功能上下文 6.1：验收层（接口响应时间）

- 任务项 6.1.1：`GET /shop/dashboard/summary`（含 6 卡片 + 24 点趋势 + 4 指标）在满量数据下整体响应 < 1s
  - 测试思路：测试库构造满量数据（如各状态订单合计 1 万条、sku 1 千条）→ 计时调用 summary 接口，断言耗时 < 1000ms。Fail 边界：无索引全表扫描时超时，功能正确但验收不过

- 任务项 6.1.2：统计口径的下游查询（order-service 的状态计数/小时聚合/指标、product-service 库存预警计数）单次响应也应纳入计时链路，聚合后总耗时受控
  - 测试思路：mock 场景不可测真实耗时，改在真实库端到端计时（或单独压测下游统计接口断言 < 300ms 为聚合留余量）。Fail 边界：单下游就超 600ms 时聚合必然超标

### 功能上下文 6.2：DAO 层性能保障（索引先行）

- 任务项 6.2.1：tb_order 建组合索引（shop_id+status、shop_id+pay_time），支撑状态计数与小时聚合走索引
  - 测试思路：插入 1 万订单后执行状态计数/小时聚合 SQL，断言耗时 < 100ms（可配合 EXPLAIN 断言命中索引）。Fail 边界：只有 shop_id 单列索引时按 status/pay_time 过滤仍会回表扫描

- 任务项 6.2.2：tb_sku 的 stock 预警查询、tb_shop_message 的 shop_id+created_at 查询加对应索引
  - 测试思路：满量数据下两类查询断言耗时 < 100ms。Fail 边界：消息表按时间倒序无索引时分页越翻越慢

注：若索引达标后仍超 1s，再按架构思考第 5 条引入 Redis 60s 聚合缓存（事件失效），不提前做。

---

## 架构与重构思考

### 组件间关系分析

```
商家浏览器 → Gateway 8081（token 鉴权，shopId 透传）
              ↓ /shop/dashboard/**
        shop-service（聚合层）
          ├─ DashboardController → DashboardService
          │     ├─ OrderStatsClient (Feign) ──→ order-service（订单状态计数/小时聚合/指标）
          │     ├─ ProductStatsClient (Feign) → product-service（库存预警计数）
          │     └─ 同比计算（纯函数）
          └─ MessageConsumer (RocketMQ) ← b2c-order-events ← order-service 事件发布
                └─ tb_shop_message（消息表）→ 消息查询接口
```

关键耦合点与重构方向：

1. **order-service 对 shop-service 的双通道耦合**（同步 Feign 统计 + 异步 MQ 事件）：两条通道的 shopId/orderId 契约各写各的，无编译期保障。重构方向：事件载荷与统计 VO 在 common 模块定义共享 DTO/常量，两端引用；topic/tag 名收敛为常量类。
2. **统计口径的时间边界（今日/昨日、支付时间归属）散落在 SQL**：一旦多处各算各的，指标对不上账。重构方向：测试全绿后把「自然日边界」提取为统一的 DateRange 工具（Asia/Shanghai），订单统计 SQL 全部引用；若后续出现周/月报表，升级为统计口径策略。
3. **同比计算是纯展示逻辑**：放 shop-service Service 且不依赖任何 IO，可直接纯函数单测。重构方向：若再出现环比/年度同比，抽 RatioCalculator，模板已按此留好位置（4.2 独立上下文）。
4. **消息文本模板**：三种文案硬编码在消费者里会随事件增多漂移。重构方向：MessageType 枚举 + 模板常量（或 MessageTemplate 接口 + 三实现，事件类型再增时走策略），与 ErrorCode 枚举（user 模块已有先例）风格一致。
5. **< 1s 非功能约束**：6 卡 + 24 点 + 4 指标若实时跨服务聚合有超时风险。重构方向：shop-service 内 Redis 缓存聚合结果（60s TTL，MQ 事件到达时失效），接口先实测再决定是否上缓存（YAGNI，先索引：order 表 shop_id+status、shop_id+pay_time 组合索引）。
6. **Feign 聚合的容错**：order-service 不可用会拖垮整个首页。重构方向：每个卡片区块独立降级（某下游失败该区块暂无数据而非整体 500），可用 Feign fallback 实现，首版可后补。

### 架构对上下文划分的影响

- **权限/隔离单独成功能点（1.x）**：「只看自己店铺」是用户可感知的安全契约，且渗透到每个查询，必须先于数据功能钉死（shopId 不信任前端参数），后续功能点的 DAO 测试全部要带 shopId 断言。
- **order/product 的 DAO 与 Service（2.1/2.2/3.1/4.1）**：计数与聚合是 SQL 行为，只能真实 SQLite 验证（临时测试库）；业务口径（支付时间归属、状态过滤）放 Service，mock DAO 穷举边界。
- **shop-service 聚合层（2.3/3.2/4.2）**：Feign 是外部依赖，mock 后验证「组装与展示逻辑」（null 语义、同比格式），与下游统计解耦，快且稳定。
- **消息链路（5.x）按事件流向切分**：发布（order）→ 消费落库（shop）→ 查询（shop）三个上下文各自独立可测，MQ 用 mock producer/嵌入式消费验证，真实端到端留给联调。
- **同比与模板文案独立成任务**：纯函数、零 IO，是最小原子任务，直接单测断言格式（+100.00% 的 + 号、两位小数）——Fail 边界最清晰。

### TDD 执行顺序建议

功能点 1（权限与隔离，安全先行）→ 功能点 2（6 卡片：先 DAO 计数红绿，再聚合 VO 的 null 语义）→ 功能点 3（小时聚合）→ 功能点 4（指标 + 同比纯函数）→ 功能点 5（事件发布 → 消费落库 → 查询）→ 功能点 6（性能：先建索引 6.2，再满量数据验收计时 6.1）→ 最后联调验证消息点击跳转（前端，后端保证 orderId 字段即可）。每个任务项独立「红 → 绿 → 重构」小循环；5.2.2 的三种消息模板应在一个绿循环内逐条点亮断言。
