-- ============================================
--  店铺表
-- ============================================
CREATE TABLE IF NOT EXISTS tb_shop
(
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    shop_name      VARCHAR(100) NOT NULL, -- 店铺名
    admin_account  varchar(100) NOT NULL, -- 管理员
    admin_password varchar(100) NOT NULL, -- 管理员密码
    logo_url       VARCHAR(500),          -- 店铺logo
    status         TINYINT  DEFAULT 1,    -- 状态：1-正常 0-禁用
    created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME DEFAULT CURRENT_TIMESTAMP
);


-- ============================================
-- 站内信表
-- ============================================
CREATE TABLE IF NOT EXISTS tb_messages
(
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    shop_id    INTEGER,
    sender_id  INTEGER,            -- 发送者（系统消息可为NULL）
    title      VARCHAR(200),       -- 消息标题
    content    TEXT,               -- 消息内容
    msg_type   TINYINT  DEFAULT 1, -- 1-系统通知 2-个人消息 3-公告
    is_read    BOOLEAN  DEFAULT 0, -- 是否已读
    read_time  DATETIME,           -- 阅读时间
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_messages_shop_id ON tb_messages (shop_id);
