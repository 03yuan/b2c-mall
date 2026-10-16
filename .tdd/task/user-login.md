# TDD 任务拆解：C 端用户用户名密码登录

## 需求概述

在电商系统中新增面向 **C 端消费者** 的登录能力：用户在登录界面输入用户名和密码，系统校验通过后签发登录凭证（Token），用户凭 Token 访问需要登录的业务接口。

已确认的边界决策：

| 决策点 | 结论 |
| --- | --- |
| 登录主体 | C 端消费者，**新建独立 user 微服务**（user.db，独立 SQLite 库），与现有 shop / employee 登录并列 |
| 安全策略 | 仅基础校验：用户名 + 密码 + 账号状态，不做失败锁定/验证码/记住我 |
| Token 方案 | 沿用现状：UUID Token 存 Redis（`b2c:token:` 前缀，24h 过期），网关 AuthFilter 校验 |
| 密码存储 | BCrypt 加密入库（与 shop / employee 一致），校验用 `BCrypt.checkpw` |

架构形态：Spring Cloud 微服务（gateway + user + shop + employee + product + order + common），每个服务内 MVC 分层（Controller/Service/Mapper/PO），网关责任链过滤器（LogginFilter → WhiteUrlFilter → AuthFilter）统一鉴权。

---

## 功能点 1：用户输入正确的用户名和密码可以登录成功

- 功能上下文 1.1：View 层 — 登录界面

  - 任务项 1.1.1：登录界面展示用户名输入框、密码输入框和登录按钮
    - 测试思路：断言登录页面渲染后存在用户名输入框、密码输入框、登录按钮的 DOM 元素
  - 任务项 1.1.2：密码输入时以掩码形式显示
    - 测试思路：断言密码输入框的 `type` 属性为 `password`
  - 任务项 1.1.3：点击登录按钮时将用户名和密码提交到 `/user/login`
    - 测试思路：Mock 后端接口，模拟填写表单并点击，断言发出的请求为 POST `/user/login` 且请求体中包含正确的 username 和 password 字段

- 功能上下文 1.2：Controller 层（user-service 的 UserController）— 接收并转发登录请求

  - 任务项 1.2.1：定义登录接口 `POST /user/login`，接收请求体中的 username 和 password
    - 测试思路：使用 MockMvc/集成测试发起合法 JSON 请求，断言返回 HTTP 200，且响应体符合 `BaseResponseVO` 结构（status、message、data 字段齐全）
  - 任务项 1.2.2：登录成功后返回 token 及用户基础信息（userId、username、nickname/avatar 等）
    - 测试思路：Mock Service 层返回含 token 的结果，断言响应 data 中包含 `token` 字段且非空，包含 `userId`、`username` 字段
  - 任务项 1.2.3：登录参数通过 `@Valid` + 请求 VO（UserLoginRequestVO）做非空校验
    - 测试思路：发起 username 或 password 为空串的请求，断言返回 HTTP 400 且 message 提示"用户名不能为空"/"密码不能为空"（验证 Fail 边界）；发起完整参数请求返回 200（验证 Pass 边界）

- 功能上下文 1.3：Service 层（UserService/UserServiceImpl）— 登录核心逻辑

  - 任务项 1.3.1：按用户名查询用户（`userMapper.selectByUsername`）
    - 测试思路：Mock Mapper 返回已知用户 PO，调用 `login()`，断言 Mapper 被以正确 username 调用一次且返回结果继续参与后续校验
  - 任务项 1.3.2：用 BCrypt 校验明文密码与库中密文（`BCrypt.checkpw(req.getPassword(), user.getPassword())`）
    - 测试思路：构造 BCrypt 加密后的密码入库，分别用正确明文和错误明文调用，断言正确明文校验通过、错误明文抛出"用户名或密码错误"异常
  - 任务项 1.3.3：校验账号状态（status == 1 表示正常）
    - 测试思路：构造 status=0（禁用）的用户调用登录，断言抛出"账号已禁用"异常；status=1 时正常通过
  - 任务项 1.3.4：登录成功后生成 UUID token，将用户信息 JSON 写入 Redis `b2c:token:{token}`，TTL 24 小时
    - 测试思路：使用嵌入式 Redis 或 mock StringRedisTemplate，登录成功后断言 `opsForValue().set` 被调用，key 为 `b2c:token:` + 返回的 token，value 含 userId 的 JSON，过期时间为 24 小时
  - 任务项 1.3.5：返回结果组装：用户信息 Map + token
    - 测试思路：调用 `login()` 断言返回 Map 同时含 token、userId、username 三个 key，且 token 与写入 Redis 的 key 后缀一致

- 功能上下文 1.4：DAO 层（UserMapper + UserPO + user.db）— 用户数据存取

  - 任务项 1.4.1：定义 tb_user 表结构（id、username、password、nickname、avatar_url、status 等），独立使用 user.db
    - 测试思路：对 user.db 执行建表后查询表结构，断言存在 username 与 password 列，且连接 URL 指向 user.db 绝对路径（与其他服务数据库文件不同）
  - 任务项 1.4.2：实现 `selectByUsername(String username)` 查询
    - 测试思路：插入一条已知用户名的记录，按该用户名查询断言返回对应 PO；查询不存在的用户名断言返回 null

- 功能上下文 1.5：View 层 — 登录成功后的页面表现

  - 任务项 1.5.1：登录成功后保存 token 并跳转到首页/来源页
    - 测试思路：Mock 登录接口返回 200 + token，断言 localStorage/sessionStorage 中存入 token，且路由跳转到首页

---

## 功能点 2：用户名不存在或密码错误时不允许登录

- 功能上下文 2.1：Service 层（UserService/UserServiceImpl）— 失败分支处理

  - 任务项 2.1.1：用户名不存在时抛出统一的"用户名或密码错误"异常（不区分用户名/密码错误，防止账号枚举）
    - 测试思路：Mock Mapper 返回 null 调用登录，断言抛出 message 为"用户名或密码错误"的异常，且断言异常信息中不包含"用户不存在"字样
  - 任务项 2.1.2：密码校验失败时抛出相同的"用户名或密码错误"异常
    - 测试思路：构造存在用户但传入错误密码，断言抛出的异常 message 与 2.1.1 完全一致（验证两类失败对外表现不可区分）
  - 任务项 2.1.3：失败时不生成 token、不写 Redis
    - 测试思路：登录失败后断言 Redis 的 set 方法从未被调用（Mock 验证零交互），返回结果中不含 token

- 功能上下文 2.2：Controller 层 — 失败响应约定

  - 任务项 2.2.1：登录失败经全局异常处理（@RestControllerAdvice）返回合适的错误响应
    - 测试思路：发起错误密码的登录请求，断言返回非 200 状态码（按项目约定 400/401）且响应体 message 为"用户名或密码错误"，错误响应结构与 BaseResponseVO 一致

- 功能上下文 2.3：View 层 — 失败提示

  - 任务项 2.3.1：登录失败时在界面上提示"用户名或密码错误"，停留在登录页
    - 测试思路：Mock 登录接口返回错误响应，点击登录后断言页面出现错误提示文案，且当前路由仍为登录页

---

## 功能点 3：登录接口本身无需登录即可访问（网关放行）

- 功能上下文 3.1：网关层（gateway 的 WhiteUrlFilter + application.yml）— 白名单配置

  - 任务项 3.1.1：在 `b2c.auth.white-urls` 白名单中加入 `/user/login`
    - 测试思路：不带 Authorization 头请求 `/user/login`，断言请求被放行至 user-service（返回业务响应而非 401）
  - 任务项 3.1.2：配置 user-service 网关路由 `Path=/user/**` → `lb://user-service`
    - 测试思路：请求 `/user/login`，断言网关将其转发到 user-service（验证 Pass 边界）；请求未配置的路径断言 404（验证 Fail 边界）
  - 任务项 3.1.3：白名单仅精确放行登录接口，`/user/**` 其他接口仍需鉴权
    - 测试思路：不带 token 请求 `/user/profile`（受保护接口），断言返回 401（验证白名单未过度放行的 Fail 边界）

---

## 功能点 4：登录成功后凭 Token 可访问受保护接口，Token 无效或过期被拒绝

- 功能上下文 4.1：网关层（gateway 的 AuthFilter）— Token 校验

  - 任务项 4.1.1：携带 `Authorization: Bearer {token}` 且 Redis 中存在 `b2c:token:{token}` 时放行请求
    - 测试思路：预置 Redis `b2c:token:abc123`，携带该 token 请求受保护接口，断言返回非 401（验证 Pass 边界）
  - 任务项 4.1.2：token 缺失或不带 Bearer 前缀时返回 401"未登录或token缺失"
    - 测试思路：不带 Authorization 头（或只传裸 token）请求受保护接口，断言 HTTP 401 且 message 为"未登录或token缺失"
  - 任务项 4.1.3：token 不存在或已过期（Redis 中查不到）时返回 401"token无效或已过期"
    - 测试思路：携带 Redis 中不存在的 token 请求受保护接口，断言 HTTP 401 且 message 为"token无效或已过期"；再验证 token TTL 到期后同样返回 401

- 功能上下文 4.2：Redis 存储 — Token 生命周期

  - 任务项 4.2.1：登录签发的 token 在 Redis 中 24 小时后自动过期
    - 测试思路：登录成功后断言 Redis key 的 TTL 在 24h 左右（>23h 且 ≤24h）；用 mock 时钟/删除 key 模拟过期，断言再取为 null（Fail 边界）

---

## 功能点 5：登录请求参数非法时被拒绝（不进入业务逻辑）

- 功能上下文 5.1：Controller 层 — 参数校验

  - 任务项 5.1.1：username 为空（null/空串/纯空格）返回 400"用户名不能为空"
    - 测试思路：发起 username 为空的请求，断言返回 400、message 为"用户名不能为空"，且 Service 层 login 方法零调用（未进入业务逻辑）
  - 任务项 5.1.2：password 为空返回 400"密码不能为空"
    - 测试思路：发起 password 为空的请求，断言返回 400、message 为"密码不能为空"，Service 层零调用
  - 任务项 5.1.3：请求体不是合法 JSON 时返回 400 而非 500
    - 测试思路：发送格式错误的 JSON 请求体，断言 HTTP 400（而非 500），全局异常处理器正确兜底

- 功能上下文 5.2：View 层 — 前端必填校验

  - 任务项 5.2.1：用户名或密码为空时点击登录，前端拦截并提示，不发起请求
    - 测试思路：留空表单点击登录，断言 Mock 的登录接口未被调用，页面出现必填提示

---

## 功能点 6：密码不得以明文形式落库（安全基线）

- 功能上下文 6.1：DAO 层 / Service 层 — 密码加密

  - 任务项 6.1.1：用户密码使用 BCrypt 加密后写入 tb_user.password（本功能由用户注册/初始化侧保证，登录侧以密文比对）
    - 测试思路：向 tb_user 插入用户后直接查询 password 列，断言其值不是原始明文且能通过 `BCrypt.checkpw(明文, 库中值)` 校验（Fail 边界：明文存储时 checkpw 应失败）

---

## 架构与重构思考

- 组件间关系分析：

  本次需求涉及的组件链路为：`View(登录页) → Gateway(WhiteUrlFilter → AuthFilter) → user-service Controller → Service → Mapper → user.db`，外挂依赖 Redis（Token 存储）。

  潜在耦合点：

  1. **Token key 前缀 `b2c:token:` 与 TTL 24h 目前在 shop、employee、gateway 三处各写一份常量**。新建 user-service 若再复制一份，将成为第 4 处重复。建议将 TokenKey 常量（甚至 Token 签发/校验组件）下沉到 `common` 模块统一维护。
  2. **登录流程高度相似**：查询用户 → BCrypt 校验 → 状态校验 → 生成 UUID token → 用户信息 JSON 写 Redis → 返回 token+userInfo。shop/employee 已各写一遍，user 是第三遍。建议后续抽取 `AuthTokenService`（common 或独立 auth 组件），登录服务只负责"取用户 + 验密"，Token 签发与存储统一处理。
  3. **user-service 是纯新服务，无历史包袱**，可直接按 `web(request/response VO) → service → dao(mapper/po)` 标准分层落位，与 employee-service 结构对齐，降低团队认知成本。

- 架构对上下文划分的影响：

  1. **微服务边界决定了"DAO/数据"上下文独立**：每服务独立 SQLite（user.db）是项目硬约束，跨库外键被禁止，因此 tb_user 必须自包含（不引用 employee/shop 的表），上下文划分时数据层单独成块并以 user.db 为验证对象。
  2. **网关的存在使"鉴权"成为独立上下文**：登录接口的免登录（WhiteUrlFilter）与登录后的 Token 校验（AuthFilter）都在 gateway 组件内完成，与 user-service 的业务校验（密码对不对）是两个正交关注点，必须分开拆解、分开测试——这也是功能点 3、4 独立于功能点 1、2 的原因。
  3. **View 与 Controller 分离**：前端表单校验（5.2）与后端参数校验（5.1）互不替代——后端校验是安全边界，前端校验是体验优化，测试各自独立。
  4. **employee 登录已有 EventBus 观察者（登录日志）**，本次 user 登录按"仅基础校验"决策不引入；但分层上 Service 预留事件发布点（登录成功/失败），未来需要登录日志、风控时以观察者方式接入，无需改动登录主流程（开闭原则）。

- 测试通过后的重构方向：

  1. 抽取 `common-auth`：统一 `b2c:token:` 前缀、TTL、`issueToken(userInfo)` / `validateToken(token)` 方法，shop/employee/user 三个登录实现收敛到同一组件，删除重复常量。
  2. 若登录流程继续复制（如后续增加第三方登录、手机号登录），可引入**模板方法模式**（与 product-service 的 7 步创建流程呼应）：`AbstractLoginService` 定义"取账号 → 验凭证 → 查状态 → 签发 Token"骨架，各登录方式只实现差异化步骤。
  3. Controller 中 IP/User-Agent 提取逻辑（employee 已有 `extractIp`）建议下沉为公共工具或参数解析器，避免 user-service 再次复制。
