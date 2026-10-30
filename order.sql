-- 订单主表（schema 变更需先删旧表重建，SQLite CREATE IF NOT EXISTS 不改表）
DROP TABLE IF EXISTS tb_order;

CREATE TABLE IF NOT EXISTS tb_order (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    order_no        VARCHAR(64)   NOT NULL UNIQUE,          -- 订单号
    shop_id         INTEGER       NOT NULL,                  -- 店铺ID
    sku_id          INTEGER       NOT NULL,                  -- 商品ID
    sku_name        VARCHAR(200)  NOT NULL,                  -- 商品名称（冗余）
    quantity        INTEGER       NOT NULL,                  -- 购买数量
    total_price     DECIMAL(10,2) NOT NULL,                  -- 总价（实付金额）
    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING', -- 订单状态：PENDING/PAID/SHIPPED/COMPLETED/FAILED/GROUP_PENDING
    pay_type        VARCHAR(20),                             -- 支付方式：ALIPAY/WECHAT/BALANCE（付款后填入）
    order_type      VARCHAR(20)   DEFAULT 'NORMAL',          -- 订单类型：NORMAL/SECKILL
    order_category  VARCHAR(20)   DEFAULT 'NORMAL',          -- 订单类别：NORMAL/CARD（电子卡券）
    logistics_type  VARCHAR(20),                             -- 物流类型：EXPRESS/CITY
    logistics_no    VARCHAR(64),                             -- 物流单号（发货后填入）
    store_name      VARCHAR(100),                            -- 门店名称（多门店模式，可空）
    shop_name       VARCHAR(100),                            -- 店铺名称（下单快照）
    shop_phone      VARCHAR(20),                             -- 店铺电话（下单快照冗余，电话图标数据源；可空）
    sku_image       VARCHAR(500),                            -- 商品图片（下单快照）
    unit_price      DECIMAL(10,2),                           -- 成交单价
    is_gift         TINYINT       DEFAULT 0,                 -- 赠品标记：0-否 1-是
    buyer_deleted   TINYINT       DEFAULT 0,                 -- 买家删除标记：0-未删除 1-已删除
    buyer_fav       TINYINT       DEFAULT 0,                 -- 买家收藏标记：0-未收藏 1-已收藏（列表不按此过滤，故不建索引）
    pay_time        DATETIME,                                -- 支付时间（趋势按支付时间归属小时）
    buyer_username  VARCHAR(50),                              -- 买家用户名（支付客户数按其去重）
    receiver_name   VARCHAR(50)   NOT NULL,                  -- 收货人
    receiver_phone  VARCHAR(20)   NOT NULL,                  -- 收货电话
    receiver_address VARCHAR(500) NOT NULL,                  -- 收货地址
    create_time     DATETIME      DEFAULT (datetime('now','localtime')),
    update_time     DATETIME      DEFAULT (datetime('now','localtime'))
);

-- 组合索引：支撑 Dashboard 状态计数与小时聚合走索引（6.2.1）
CREATE INDEX idx_order_shop_status ON tb_order (shop_id, status);
CREATE INDEX idx_order_shop_pay_time ON tb_order (shop_id, pay_time);
-- 组合索引：支撑商家端下单时间区间筛选（shop-order-list 1.1.2）
CREATE INDEX idx_order_shop_create_time ON tb_order (shop_id, create_time);

-- 退款申请单据表（Dashboard「退款/售后」卡片统计用；schema 变更先删旧表重建）
DROP TABLE IF EXISTS tb_refund;

CREATE TABLE IF NOT EXISTS tb_refund (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    order_id        INTEGER       NOT NULL,                  -- 关联订单ID
    shop_id         INTEGER       NOT NULL,                  -- 店铺ID
    status          VARCHAR(20)   NOT NULL,                  -- 状态：PROCESSING=进行中/DONE=已完成
    amount          DECIMAL(10,2) NOT NULL,                  -- 退款金额
    create_time     DATETIME      DEFAULT (datetime('now','localtime'))
);

-- 订单状态变更日志
CREATE TABLE IF NOT EXISTS tb_order_log (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    order_id        INTEGER       NOT NULL,                  -- 订单ID
    from_status     VARCHAR(20),                              -- 变更前状态
    to_status       VARCHAR(20)   NOT NULL,                   -- 变更后状态
    event           VARCHAR(20)   NOT NULL,                   -- 触发事件：PAY/SHIP/CONFIRM
    operator_id     INTEGER,                                  -- 操作人ID（来自token）
    remark          VARCHAR(500),                             -- 备注
    create_time     DATETIME      DEFAULT (datetime('now','localtime'))
);
