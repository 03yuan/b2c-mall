-- 订单主表
CREATE TABLE IF NOT EXISTS tb_order (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    order_no        VARCHAR(64)   NOT NULL UNIQUE,          -- 订单号
    shop_id         INTEGER       NOT NULL,                  -- 店铺ID
    sku_id          INTEGER       NOT NULL,                  -- 商品ID
    sku_name        VARCHAR(200)  NOT NULL,                  -- 商品名称（冗余）
    quantity        INTEGER       NOT NULL,                  -- 购买数量
    total_price     DECIMAL(10,2) NOT NULL,                  -- 总价
    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING', -- 订单状态：PENDING/PAID/SHIPPED/COMPLETED
    pay_type        VARCHAR(20),                             -- 支付方式：ALIPAY/WECHAT（付款后填入）
    receiver_name   VARCHAR(50)   NOT NULL,                  -- 收货人
    receiver_phone  VARCHAR(20)   NOT NULL,                  -- 收货电话
    receiver_address VARCHAR(500) NOT NULL,                  -- 收货地址
    create_time     DATETIME      DEFAULT (datetime('now','localtime')),
    update_time     DATETIME      DEFAULT (datetime('now','localtime'))
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
