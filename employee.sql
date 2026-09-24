
-- ============================================
-- 员工
-- ============================================
CREATE TABLE IF NOT EXISTS tb_employee (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    shop_id INTEGER not null ,
    username VARCHAR(50) NOT NULL,           -- 账号
    password VARCHAR(255) NOT NULL,            -- 密码哈希
    avatar_url VARCHAR(500) DEFAULT '/avatars/default.png',  -- 头像地址
    last_login_time DATETIME,                       -- 最后登录时间
    login_count INTEGER DEFAULT 0,                  -- 累计登录次数
    status TINYINT DEFAULT 1,                       -- 状态：1-正常 0-禁用
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_shop_id ON tb_employee(shop_id);


-- ============================================
--  登录日志表（安全审计）
-- ============================================
CREATE TABLE IF NOT EXISTS tb_login_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER,                                -- 登录用户ID（失败时可为空）
    login_ip VARCHAR(50),                           -- 登录IP
    login_device VARCHAR(255),                      -- 登录设备
    login_location VARCHAR(255),                    -- 登录地点
    login_time DATETIME DEFAULT CURRENT_TIMESTAMP,  -- 登录时间
    user_agent VARCHAR(500),                        -- 浏览器UA
    status TINYINT DEFAULT 1                        -- 1-成功 0-失败
    -- user_id 与 tb_employee.id 的关联由应用层保证（微服务拆库后不使用数据库跨表外键）
);

CREATE INDEX idx_login_logs_user_id ON tb_login_logs(user_id);
CREATE INDEX idx_login_logs_login_time ON tb_login_logs(login_time);

