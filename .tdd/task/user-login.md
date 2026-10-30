# 用户登录功能 TDD 拆解文档（user-service）

## 需求概述

C 端用户使用用户名 + 密码登录电商系统。登录成功后签发 token（写入 Redis，24 小时有效）并返回用户基本资料；登录失败（用户名不存在 / 密码错误 / 账号禁用）返回统一的业务错误，不泄露账号是否存在。

**架构背景**：Maven 多模块微服务。请求经 Spring Cloud Gateway（`/user/**` 路由已配置，`/user/login` 已在鉴权白名单）转发至 user-service。user-service 采用分层架构：`web`（Controller + 请求 VOs + 全局异常处理）→ `service`（业务逻辑）→ `dao/mapper` + `dao/po`（MyBatis 数据访问，SQL 在 `resources/mapper/*.xml`）→ SQLite（user.db，独立库）。token 存 Redis（前缀 `b2c:token:`，与网关 AuthFilter 校验逻辑一致）。

**已确认的决策**：
- user 模块目录已被删除，本次拆解包含模块脚手架任务
- 保持轻量：不做员工登录式的 observer 审计事件
- 登录成功返回：token + 用户信息（userId、username、nickname、avatarUrl）
- 密码为明文比对（user.sql 种子数据 test/123456 为明文，与员工服务的 BCrypt 不同）
- 统一错误文案「用户名或密码错误」；禁用账号返回「账号已禁用」

---

## 功能点 0：user 模块脚手架重建（前置）

### 功能上下文 0.1：Maven 模块工程（构建层）

- 任务项 0.1.1：创建 `user/pom.xml`，parent 指向根 pom，artifactId=user，声明依赖（spring-boot-starter-web、spring-boot-starter-validation、mybatis-spring-boot-starter、sqlite-jdbc、spring-boot-starter-data-redis、springdoc-openapi-ui 1.7.0、hutool、common 模块、lombok），`spring-boot-starter-test` 放在 test scope
  - 测试思路：`mvn -pl user test` 能通过依赖解析并执行测试。Fail 边界：依赖版本未在父 pom dependencyManagement 声明时模块解析失败（IDE 红文件、包找不到）；Pass：构建成功

- 任务项 0.1.2：校验根 pom 的 `<module>user</module>` 声明仍存在
  - 测试思路：`mvn validate` 聚合构建不报 unknown module；Fail 边界：根 pom 缺声明则 user 模块不会参与聚合构建

### 功能上下文 0.2：启动类与运行配置（应用层）

- 任务项 0.2.1：创建 `com.ysh.b2cmall.user.UserApplication` 启动类（`@SpringBootApplication` + `@MapperScan("com.ysh.b2cmall.user.dao.mapper")`）
  - 测试思路：`@SpringBootTest` 上下文加载测试通过；Fail 边界：MapperScan 包路径错误导致 Mapper Bean 注入失败

- 任务项 0.2.2：创建 `resources/application.yml`：端口 8086（8081-8085 已被其他服务占用）、application.name=user-service、Nacos 配置同 employee 模块、数据源 `jdbc:sqlite:C:/Users/白鸽/Desktop/b2c/user.db`（绝对路径）、Redis、`mybatis.mapper-locations: classpath:mapper/*.xml`、springdoc 配置
  - 测试思路：上下文加载后数据源指向已存在的 user.db。Fail 边界：使用相对路径会在启动目录误建空库，导致后续查表抛「no such table」

### 功能上下文 0.3：表结构（数据层）

- 任务项 0.3.1：确认 user.sql 的 tb_user 表（id、username 唯一索引、password、nickname、avatar_url、status、created_at、updated_at）已初始化，且含种子用户 test/123456
  - 测试思路：通过 mapper 查询 `username='test'` 能返回记录。Fail 边界：库文件缺失或表为空时查询返回 null

---

## 功能点 1：用户输入正确用户名密码可以登录

### 功能上下文 1.1：Controller 层（web/UserController + web/request/LoginRequestVO）

- 任务项 1.1.1：提供 `POST /user/login` 接口，接收 JSON 请求体 LoginRequestVO（username、password 两个字段）
  - 测试思路：MockMvc 发起 POST /user/login（合法请求体）→ 断言 HTTP 200 且返回 BaseResponseVO（status=200）。Fail 边界：未实现路由时返回 404

- 任务项 1.1.2：登录请求体参数校验：username、password 缺失或为空 → HTTP 400 + 中文提示（`@Valid` + `@NotBlank`）
  - 测试思路：分别发送空 username / 空 password / 缺字段的请求 → 断言 HTTP 400，且响应 message 非空。Fail 边界：未加校验注解时参数会穿透到 Service 层返回 200 或触发其他异常

- 任务项 1.1.3：成功响应的 data 中包含 token、userId、username、nickname、avatarUrl 五个字段
  - 测试思路：成功登录响应的 JSON 断言上述五个 key 均存在且 token 非空字符串。Fail 边界：Service 返回 Map 缺字段时断言失败

### 功能上下文 1.2：Service 层（service/UserService + service/impl/UserServiceImpl）

- 任务项 1.2.1：按用户名查询用户，返回 UserPO 作为登录主体
  - 测试思路：mock UserMapper.selectByUsername 返回构造的 UserPO → 断言后续流程使用该 PO 的字段。Fail 边界：mapper 未被调用或查询字段传错（verify 调用参数为请求中的 username）

- 任务项 1.2.2：密码比对（明文 equals），匹配则继续后续流程
  - 测试思路：PO 密码与请求密码一致 → 不抛异常、进入 token 签发；不一致 → 抛出 RuntimeException。Fail 边界：比对用错字段（如拿 nickname 比对）时正确密码也被拒

- 任务项 1.2.3：状态校验，`status == 1` 才放行登录
  - 测试思路：status=1 → 正常通过；status=0 → 抛异常。Fail 边界：未判断 status 或判反条件时禁用账号仍能登录

- 任务项 1.2.4：成功后生成 token（UUID 去横线，32 位十六进制字符串）
  - 测试思路：断言返回 Map 中 token 匹配 `^[0-9a-f]{32}$`。Fail 边界：直接使用含横线的 UUID 字符串会带 4 个 `-`

- 任务项 1.2.5：组装登录结果 Map：token + userId、username、nickname、avatarUrl（值取自 UserPO）
  - 测试思路：断言 Map 各 key 的值与传入 PO 的 id、username、nickname、avatarUrl 完全一致。Fail 边界：字段映射写错（如 key 拼写错误）导致前端拿不到数据

### 功能上下文 1.3：数据访问层（dao/mapper/UserMapper + dao/po/UserPO + resources/mapper/UserMapper.xml）

- 任务项 1.3.1：UserPO 与 tb_user 表的字段映射（id、username、password、nickname、avatarUrl、status、createdAt、updatedAt）
  - 测试思路：基于真实 SQLite（临时测试库）插入并查询，断言 PO 各字段值与插入值一致（下划线字段 avatar_url/created_at/updated_at 正确映射为驼峰）。Fail 边界：映射开启/字段名不匹配时返回 null 字段

- 任务项 1.3.2：UserMapper 提供 `selectByUsername(String username)`，SQL 写在 `resources/mapper/UserMapper.xml`
  - 测试思路：测试库插入用户后按 username 查询命中；查询不存在的用户名返回 null。Fail 边界：SQL 写错（如表名/列名拼写）抛 SQLException

### 功能上下文 1.4：缓存层（Redis token 签发）

- 任务项 1.4.1：登录成功后写入 Redis：key=`b2c:token:{token}`，value=用户信息 JSON，TTL=24 小时
  - 测试思路：mock StringRedisTemplate，断言 `opsForValue().set` 被调用且 key 以 `b2c:token:` 开头、过期参数为 24 与 TimeUnit.HOURS、value 可反序列化出 userId/username。Fail 边界：TTL 单位写错（按秒传 24）或 key 前缀与网关 AuthFilter 校验的 `b2c:token:` 不一致时，登录后访问受保护接口会被网关拒绝

### 功能上下文 1.5：全局异常处理（web/GlobalExceptionHandler）

- 任务项 1.5.1：业务异常（登录失败）映射为 HTTP 400 + 中文 message，不暴露堆栈
  - 测试思路：触发一次失败登录 → 断言 HTTP 400 且 message 为业务文案。Fail 边界：未处理异常时框架返回 500

- 任务项 1.5.2：非法 JSON 请求体（HttpMessageNotReadableException）→ HTTP 400
  - 测试思路：发送畸形 JSON（如 `{"username":`）→ 断言 HTTP 400。Fail 边界：只捕获业务异常时该场景返回 500

---

## 功能点 2：用户输入错误用户名或密码不能登录

### 功能上下文 2.1：Controller + Service 层（失败分支契约）

- 任务项 2.1.1：用户名不存在 → 抛出带文案「用户名或密码错误」的业务异常，HTTP 400
  - 测试思路：mock mapper 返回 null，登录请求 → 断言 HTTP 400、message=「用户名或密码错误」。Fail 边界：文案不同（如「用户不存在」）则泄露账号是否存在

- 任务项 2.1.2：密码错误 → 抛出的异常文案与用户名不存在**完全一致**（防账号枚举）
  - 测试思路：两种失败场景分别断言响应 message 字符串相等。Fail 边界：文案拼写漂移（多一个空格或标点）即可被枚举

- 任务项 2.1.3：任何失败分支都不得签发 token、不得写 Redis
  - 测试思路：两种失败场景 mock StringRedisTemplate，verify 从未调用 `opsForValue`。Fail 边界：成功后才写 Redis 的位置放错（写在查询前），失败时产生孤儿 token

- 任务项 2.1.4：失败响应不包含任何用户信息字段
  - 测试思路：失败响应断言 data 为 null 且无 userId/username/nickname。Fail 边界：异常前已部分组装结果对象导致信息泄露

---

## 功能点 3：禁用账号不允许登录

### 功能上下文 3.1：Service 层（账号状态分支）

- 任务项 3.1.1：status != 1 → 抛出带文案「账号已禁用」的业务异常，HTTP 400，且不签发 token
  - 测试思路：构造 status=0 的 UserPO，断言 HTTP 400、message=「账号已禁用」、Redis 无写入。Fail 边界：状态判断缺失时禁用账号照常登录；文案与「用户名或密码错误」混用时客服无法区分问题类型

- 任务项 3.1.2：校验顺序先密码后状态（密码错误时即使账号禁用也返回统一文案，不泄露禁用状态）
  - 测试思路：status=0 且密码也错误 → 断言返回「用户名或密码错误」而非「账号已禁用」。Fail 边界：先判状态则攻击者可借文案差异识别禁用账号

---

## 功能点 4：登录接口经网关白名单放行（验证型功能点，无需开发）

### 功能上下文 4.1：网关层（gateway 配置，已存在）

- 任务项 4.1.1：验证 gateway/application.yml 中 `b2c.auth.white-urls` 含 `/user/login`，且 routes 中 `/user/**` 指向 lb://user-service
  - 测试思路：端到端手工/集成验证：不携带任何 Authorization 头，经网关 8081 请求 /user/login → 不被 AuthFilter 拦截（非 401），能到达 user-service 返回 200/400 业务结果。Fail 边界：白名单路径写错（如 `/user/login/**` 或大小写）则网关直接返回 401

### 功能上下文 4.2：网关 + 服务间契约（token 格式兼容）

- 任务项 4.2.1：登录写入 Redis 的 token value（用户信息 JSON 字符串）能被网关 AuthFilter 正常解析
  - 测试思路：登录成功后，携带返回的 token 经网关访问任一下游受保护接口 → 被放行（非 401）。Fail 边界：value 写成了非 JSON（如直接存 userId 数值），AuthFilter 解析逻辑不兼容

---

## 架构与重构思考

### 组件间关系分析

```
客户端 → Gateway 8081
          ├─ WhiteUrlFilter：/user/login 白名单放行（已配置）
          └─ 其余请求 AuthFilter：查 Redis b2c:token:{token} 校验
                ↓ /user/login
        user-service 8086
          UserController（参数校验 @Valid）
            → UserServiceImpl（查库 → 明文比对 → status==1 → 签发 token）
                ├─ UserMapper.selectByUsername → SQLite user.db（tb_user）
                └─ StringRedisTemplate → Redis（b2c:token:xxx, 24h）
```

关键耦合点：

1. **双登录实现并存**：employee 登录（BCrypt + 审计事件 + shopId 维度）与 user 登录（明文 + 轻量 + username 全局唯一）流程骨架相同（查用户 → 校验 → 签发 token → 写 Redis）。当前两处各自实现，`b2c:token:` 前缀、24h TTL、UUID 生成逻辑已重复。
   - 重构方向：待登录功能测试全绿后，将「token 生成 → 写 Redis」抽取为 common 模块的 TokenIssuer 组件，两个服务注入复用；前缀与 TTL 收敛为单一配置源，避免未来改 TTL 漏改一处。

2. **明文密码是安全债**：user.sql 种子数据为明文，与 employee 的 BCrypt 不一致。
   - 重构方向：Service 层的比对逻辑只依赖「比对结果」，测试断言行为而非实现（1.2.2 的测试思路已按此设计）。后续引入 PasswordMatcher 接口（当前 PlainTextPasswordMatcher 实现），升级为 BCrypt 时仅替换实现类与存量数据迁移，测试用例不用改。注册功能（已拆解）落地时应同时通过该接口写入密码，从源头收口。

3. **防枚举文案的 magic string**：「用户名或密码错误」在多个任务项中被引用，手工复制易漂移。
   - 重构方向：提取常量/枚举（如 LoginErrorCode），任务项 2.1.2 的测试直接断言常量相等，把防枚举约束固化到代码结构而非个人纪律。

4. **与网关 AuthFilter 的隐性契约**：token 的 value 内容与 AuthFilter 的解析逻辑必须兼容（功能点 4.2）。这是跨进程的「配置式契约」，无编译期保障。
   - 重构方向：契约靠功能点 4 的端到端验证兜底；若后续 AuthFilter 需要读取 token 中的具体字段，应将 value 的结构（JSON keys）在 common 模块定义常量，两端引用而非各写各的字符串。

### 架构对上下文划分的影响

- **Controller 层（1.1）**只关心 HTTP 契约（路径、方法、参数校验、响应包装），不掺业务规则，因此用 MockMvc 做集成式断言，直接固化对外 API 形状——这是需求可感知的边界。
- **Service 层（1.2 / 2.1 / 3.1）**是业务规则唯一载体（密码比对、防枚举、状态分支），依赖全部 mock（Mapper、Redis），测试快、可穷举分支，是 TDD 红绿循环的主战场。
- **数据访问层（1.3）**的 MyBatis XML 映射只能靠真实 SQLite 验证（用临时测试库，不污染 user.db），与 Service 隔离后，业务规则测试不受 SQL 方言影响。
- **缓存层（1.4）**把 Redis 当作外部依赖：单测用 verify 断言写入行为，功能点 4.2 用端到端验证覆盖与网关的真实兼容性——分层后「签发了什么」与「网关认不认」各自独立可测。
- **全局异常处理（1.5）**是横切关注点，单独成上下文，让所有失败分支的 HTTP 状态码语义（400 vs 500）集中在一处可测。

### TDD 执行顺序建议

按「从外到内红绿、从内到外落地」推进：先写功能点 1.1 的 Controller 验收测试（红）→ 依赖倒推实现 1.3 DAO 与 1.2 Service（每个任务项独立完成红-绿-重构小循环）→ 1.4 Redis → 1.1 转绿 → 再做功能点 2、3 的失败分支（此时 Service 已存在，全部是分支补充的小循环）→ 最后功能点 4 端到端验证。每个任务项都要经历：先写断言（红，看到 Fail）→ 最小实现（绿）→ 顺手消除坏味道（重构，测试仍绿）。