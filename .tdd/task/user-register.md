# 用户注册功能 TDD 拆解文档（user-service）

## 需求概述

C 端用户使用用户名 + 密码注册账号。注册成功后返回 userId（不自动登录，用户需再调 POST /user/login 获取 token）。用户名全局唯一，重复注册返回明确错误；用户名/密码不符合规则时返回 400 中文提示。

**架构背景**：与登录功能同一线程（见 `user-login.md`）。请求经 Spring Cloud Gateway（`/user/**` 路由已配置）转发至 user-service 8086，分层结构：`web`（Controller + VOs + 全局异常处理）→ `service` → `dao/mapper` + `dao/po` → SQLite（user.db）。注册流程**不涉及 Redis/token**，比登录少一个缓存上下文。

**已确认的决策**：
- 注册字段：username、password 必填；nickname 选填，不填时默认取用户名
- 注册成功后仅返回 userId，不签发 token
- 校验规则：用户名 3-20 位字母/数字/下划线；密码 6-20 位；不符返回 400 中文提示
- 密码明文存储（与 user.sql 种子数据、登录比对口径一致）
- 注册路径 `/user/register` 需新增到网关鉴权白名单（当前白名单只有 `/user/login`）

**对登录文档的依赖（不重复拆解）**：
- user 模块脚手架（pom、UserApplication、application.yml、tb_user 校验）见 `user-login.md` 功能点 0
- `UserMapper.selectByUsername` 见 `user-login.md` 任务项 1.3.2（注册的唯一性预检复用它）
- 全局异常处理器（业务异常 400、非法 JSON 400）见 `user-login.md` 功能上下文 1.5，注册直接复用，不新建

---

## 功能点 1：注册路径经网关白名单放行（网关配置）

### 功能上下文 1.1：网关层（gateway/application.yml）

- 任务项 1.1.1：在 `b2c.auth.white-urls` 中新增 `/user/register` 条目
  - 测试思路：端到端验证：不携带 Authorization 头经网关 8081 请求 POST /user/register → 不被 AuthFilter 拦截（非 401）。Fail 边界：白名单漏配，网关直接 401，注册请求到不了 user-service

---

## 功能点 2：用户提交合法信息可以注册成功

### 功能上下文 2.1：Controller 层（web/UserController + web/request/RegisterRequestVO）

- 任务项 2.1.1：提供 `POST /user/register` 接口，接收 JSON 请求体 RegisterRequestVO（username、password、nickname 三个字段，nickname 可为 null）
  - 测试思路：MockMvc 发起 POST /user/register（合法请求体）→ 断言 HTTP 200。Fail 边界：未实现路由时返回 404

- 任务项 2.1.2：注册成功响应的 data 为新建用户的 userId（正整数）
  - 测试思路：成功响应的 JSON 断言 data 为非空整数（如 1、2…），与测试库中插入记录的 id 一致。Fail 边界：返回 null 或 0 时说明 insert 未回填自增主键

### 功能上下文 2.2：Service 层（service/UserService + service/impl/UserServiceImpl）

- 任务项 2.2.1：用户名唯一性预检：先 `selectByUsername`，命中已有用户时拒绝注册
  - 测试思路：mock mapper 的 selectByUsername 返回已有 UserPO → 断言抛业务异常且 mapper 的 insert 从未被调用（verify zero interactions）。Fail 边界：跳过预检直接 insert 时，重复用户名依赖数据库唯一索引报 500

- 任务项 2.2.2：组装 UserPO 写入字段：password 明文存储、nickname 为 null 时默认取 username、avatarUrl 默认 `/avatars/default.png`、status 置 1
  - 测试思路：mock mapper，断言传给 insert 的 PO：password 与请求一致、nickname 正确回填、avatarUrl 与 status 为默认值。Fail 边界：nickname 回填逻辑缺失时入库为 null，后续登录响应 nickname 为空

- 任务项 2.2.3：调用 `UserMapper.insert(po)` 落库并返回自增主键作为 userId
  - 测试思路：mock mapper.insert 返回 1 且回填 po.id=5 → 断言 register 方法返回 5。Fail 边界：返回 mapper 的行数（1）而非 po.getId()（5），导致 userId 恒为 1

### 功能上下文 2.3：数据访问层（dao/mapper/UserMapper + resources/mapper/UserMapper.xml）

- 任务项 2.3.1：UserMapper 提供 `insert(UserPO)`，SQL 配置 `useGeneratedKeys="true" keyProperty="id"`，自增主键回填到 PO
  - 测试思路：真实 SQLite 临时库执行 insert → 断言 po.getId() 非空且递增，再用 selectByUsername 能查回同一条记录。Fail 边界：未开 useGeneratedKeys 时 po.getId() 为 null

---

## 功能点 3：重复用户名不能注册

### 功能上下文 3.1：Controller + Service 层（唯一性失败分支）

- 任务项 3.1.1：用户名已存在 → 抛出带文案「用户名已存在」的业务异常，HTTP 400
  - 测试思路：测试库预置用户名 `test`（种子数据）→ 再次用 `test` 注册 → 断言 HTTP 400、message=「用户名已存在」。Fail 边界：文案含糊（如「注册失败」）时前端无法提示用户换名

- 任务项 3.1.2：唯一性由应用层预检 + 数据库唯一索引双重保障，索引兜底异常同样转为「用户名已存在」而非 500
  - 测试思路：模拟绕过预检的场景，让 insert 抛唯一约束冲突 → 断言全局异常兜底后返回 400「用户名已存在」。Fail 边界：并发下预检双双通过，insert 冲突异常未被捕获，用户看到 500 而不是可读提示

- 任务项 3.1.3：重复注册失败时不产生任何脏数据
  - 测试思路：失败后断言测试库中该 username 仍只有预置的一条记录。Fail 边界：先 insert 后校验的实现顺序会插出第二条记录

---

## 功能点 4：字段不符合规则不能注册

### 功能上下文 4.1：Controller 层（请求体参数校验）

- 任务项 4.1.1：username 校验：必填，3-20 位，仅字母/数字/下划线（`@NotBlank` + `@Pattern(regexp = "^[a-zA-Z0-9_]{3,20}$")`）
  - 测试思路：分别发送空/2 位/21 位/含中文或特殊字符的 username → 断言 HTTP 400 且 message 含中文提示。Fail 边界：正则写反边界（如 `{0,20}`）时超长用户名穿透到服务层

- 任务项 4.1.2：password 校验：必填，6-20 位（`@NotBlank` + `@Size(min = 6, max = 20)`）
  - 测试思路：发送 5 位、21 位、空 password → 断言 HTTP 400。Fail 边界：只做 @NotBlank 时 5 位弱密码也能注册

- 任务项 4.1.3：nickname 选填，但传入时不超过 50 字符（tb_user.nickname 为 VARCHAR(50)）
  - 测试思路：传 51 字符 nickname → 断言 HTTP 400；不传 nickname → 正常注册且入库 nickname 取用户名。Fail 边界：不限制长度时超长 nickname 入库抛 SQL 异常返回 500

- 任务项 4.1.4：参数校验失败的响应不调用 Service 层
  - 测试思路：mock Service，校验失败请求 → verify service 零调用。Fail 边界：校验放 Service 内部手工 if 判断时，Controller 测试覆盖不到的分支会漏掉

### 功能上下文 4.2：全局异常处理（复用 login 文档 1.5，仅验证）

- 任务项 4.2.1：校验失败（MethodArgumentNotValidException）→ HTTP 400 + 中文提示
  - 测试思路：任一非法输入请求 → 断言 HTTP 400，message 非空。Fail 边界：handler 未覆盖该校验异常时返回 500

- 任务项 4.2.2：畸形 JSON 请求体 → HTTP 400（与登录共用 handler 的既有行为）
  - 测试思路：发送 `{"username":` 畸形 JSON → 断言 HTTP 400。Fail 边界：注册上前不验证该场景会误以为 handler 被登录独占而回归

---

## 功能点 5：注册成功后可登录（验证型功能点，依赖登录功能已完成）

### 功能上下文 5.1：跨功能闭环（注册 → 登录）

- 任务项 5.1.1：用注册时相同的 username/password 调用 POST /user/login 能登录成功并返回 token
  - 测试思路：端到端验证：注册新账号 → 立即调登录接口 → 断言 HTTP 200 且返回 token（说明注册写入的密码格式与登录比对口径一致）。Fail 边界：注册写入了某种编码/加密而登录按明文比对（或反之），闭环断裂

---

## 架构与重构思考

### 组件间关系分析

```
客户端 → Gateway 8081
          └─ WhiteUrlFilter：/user/register 白名单（本功能新增）
                ↓ /user/register
        user-service 8086
          UserController（@Valid/@Pattern/@Size 输入边界校验）
            → UserServiceImpl（selectByUsername 预检 → 组装 PO → insert）
                └─ UserMapper → SQLite user.db（tb_user，username 唯一索引兜底）
        （无 Redis 参与——注册不签发 token）
```

关键耦合点：

1. **注册与登录共享「密码」契约，是最危险的隐性耦合**：注册写入的密码格式必须与登录比对口径永久一致。注册写明文、登录比明文（当前决策）；若未来引入 BCrypt 而只改注册不改登录，则所有存量用户全线登录失败。
   - 重构方向：与 `user-login.md` 重构思考第 2 条合并——引入 PasswordMatcher 接口（明文实现），注册的「写」与登录的「比」都过同一接口，测试一并断言闭环（功能点 5.1 正是这个契约的守护测试）。换加密算法时只改一个实现类。

2. **唯一性预检与数据库唯一索引的责任分工**：预检负责友好文案（可测试、快），唯一索引负责并发兜底（不可绕过）。二者缺一不可。
   - 重构方向：唯一约束冲突的异常转换（3.1.2 的兜底）建议收敛到全局异常处理器或 mapper 层的单一 catch 点，避免每个插入业务各自写一遍 try-catch。

3. **注册与登录对 UserMapper 的复用**：selectByUsername 由登录文档定义、注册复用；insert 为本功能新增。两个 TDD 文档存在执行顺序依赖。
   - 重构方向：文档交叉依赖本身无害，但实现时注意 UserService 同时承载 register 与 login 两个职责后，若继续加「修改密码」「查询资料」会膨胀——届时按「命令/查询」或按用例拆 Service（如 RegisterService / LoginService），测试随职责迁移，本轮不提前拆（YAGNI）。

4. **校验规则的载体选择**：用户名/密码规则固化在 Controller 层注解的正则里，属于输入边界契约。
   - 重构方向：若后续出现第二个需要校验用户名的接口（如「修改用户名」「找回密码」），把正则提取为 common 或 user 模块的常量/工具，避免正则漂移导致同一系统内规则不一致。

5. **网关白名单的膨胀趋势**：每次新增公开接口都要改 gateway 配置（`/user/login`、`/user/register`…）。
   - 重构方向：暂维持显式列举（安全默认拒绝）；当公开接口超过约 10 条时，可改为白名单 + 通配前缀托管（如集中维护一份 user-service 公开路径清单），但需评估误放行风险，本轮不做。

### 架构对上下文划分的影响

- **Controller 层（2.1 / 4.1）**：注册的对外契约是「接什么、拒什么」——参数存在性、格式规则（@Pattern/@Size）、响应形状都在这里固化，用 MockMvc 断言，且要求校验失败时 Service 零调用（4.1.4），把「输入合法性」和「业务规则」的边界钉死。
- **Service 层（2.2 / 3.1）**：唯一性、回填默认值（nickname/avatar/status）、主键回填转发都是业务规则，mock Mapper 后穷举分支——这是注册功能红绿循环的主战场。
- **数据访问层（2.3）**：insert + 自增主键回填是 MyBatis 行为，只能靠真实 SQLite 验证（临时库）；与 Service 隔离后，业务测试不依赖数据库状态。
- **网关层单独成功能点（1.1）**：放行配置虽是一行 YAML，但它是「用户可感知」功能的必经之路，漏配时功能整体不可用，必须作为独立验收点存在。
- **跨功能闭环（功能点 5）**：注册的最终价值在「注册后能登录」，端到端验证放在最后一个功能点，作为两个文档契约一致性的守护。

### TDD 执行顺序建议

功能点 1（网关白名单，配置先行）→ 功能点 2.1 的 Controller 验收测试（红）→ 2.3 DAO insert（真实库验证）→ 2.2 Service（mock 验证）→ 2.1 转绿 → 功能点 3 重复分支（预检 + 兜底）→ 功能点 4 校验规则边界 → 功能点 5 闭环验证（需登录功能已实现）。每个任务项独立走「红 → 绿 → 重构」小循环；功能点 2.2.2/2.2.3 属于同一 service 方法内的不同断言面，应在一个绿循环内逐条点亮断言，而不是拆成多次实现。