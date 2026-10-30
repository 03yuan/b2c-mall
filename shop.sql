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

-- ============================================
-- 店铺消息表（订单事件消费落库，Dashboard 消息通知用）
-- schema 变更需先删旧表重建（SQLite CREATE IF NOT EXISTS 不改表）
-- ============================================
DROP TABLE IF EXISTS tb_shop_message;

CREATE TABLE IF NOT EXISTS tb_shop_message
(
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    shop_id    INTEGER      NOT NULL,                  -- 店铺ID
    type       VARCHAR(20)  NOT NULL,                  -- 事件类型：PAY_SUCCESS/REFUND_APPLY/CONFIRM_RECEIPT
    content    VARCHAR(500) NOT NULL,                  -- 消息文本（固定模板拼出，逐字不得漂移）
    order_id   INTEGER      NOT NULL,                  -- 关联订单ID（前端点击跳订单详情）
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 组合索引：支撑消息倒序分页查询走索引，防止深翻页越翻越慢（6.2.2）
CREATE INDEX idx_shop_message_shop_created ON tb_shop_message (shop_id, created_at);
