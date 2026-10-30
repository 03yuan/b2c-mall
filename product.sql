-- ============================================
--  商品类目表
-- ============================================
CREATE TABLE IF NOT EXISTS tb_category (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    shop_id    INTEGER NOT NULL,                  -- 所属店铺
    name       VARCHAR(100) NOT NULL,             -- 类目名称
    parent_id  INTEGER DEFAULT 0,                 -- 父类目ID（0为顶级）
    sort       INTEGER DEFAULT 0,                 -- 排序
    status     TINYINT DEFAULT 1,                 -- 1-正常 0-禁用
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_category_shop_id ON tb_category (shop_id);


-- ============================================
--  商品 SKU 表（schema 变更需先删旧表重建，SQLite CREATE IF NOT EXISTS 不改表）
-- ============================================
DROP TABLE IF EXISTS tb_sku;

CREATE TABLE IF NOT EXISTS tb_sku (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    shop_id     INTEGER NOT NULL,                  -- 所属店铺
    category_id INTEGER NOT NULL,                  -- 类目ID
    name        VARCHAR(100) NOT NULL,             -- 商品名称
    description TEXT,                              -- 商品描述
    price       DECIMAL(10,2) NOT NULL,            -- 销售价（前台售卖价格）
    market_price DECIMAL(10,2),                    -- 市场价（划线原价）
    stock       INTEGER,                           -- 仓库库存（虚拟商品可为空=无限库存）
    stock_threshold INTEGER,                       -- 库存预警阈值（库存低于该值计入预警）
    sales       INTEGER DEFAULT 0,                 -- 商品销量（真实成交，MQ 累加）
    fake_sales  INTEGER DEFAULT 0,                 -- 注水销量（商家可配置）
    store_stock INTEGER DEFAULT 0,                 -- 门店库存
    type        TINYINT NOT NULL,                  -- 商品类别 1-实物 2-虚拟 3-卡券
    product_type TINYINT,                          -- 商品类型 1-普通商品 2-秒杀商品
    images      VARCHAR(1000),                     -- 图片URL，逗号分隔
    spec        VARCHAR(500),                      -- 规格参数
    delivery_methods VARCHAR(100),                 -- 配送方式（逗号分隔：CITY/PICKUP/EXPRESS）
    sort        INTEGER DEFAULT 0,                 -- 排序（数字越大前台越靠前）
    status      TINYINT DEFAULT 0,                 -- 0-下架 1-上架
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sku_shop_id ON tb_sku (shop_id);
CREATE INDEX idx_sku_category_id ON tb_sku (category_id);

-- 组合索引：支撑库存预警计数走索引（6.2.2）
CREATE INDEX idx_sku_shop_stock ON tb_sku (shop_id, stock);


-- ============================================
--  商品操作日志表
-- ============================================
CREATE TABLE IF NOT EXISTS tb_sku_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    sku_id      INTEGER NOT NULL,                  -- 商品ID
    operator_id INTEGER,                           -- 操作人ID（员工ID）
    action      VARCHAR(50) NOT NULL,              -- 操作类型：CREATE/UPDATE/PUT_ON_SALE/PUT_OFF_SALE
    content     TEXT,                              -- 操作内容描述
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sku_log_sku_id ON tb_sku_log (sku_id);

-- ============================================
--  MQ 消费幂等去重表（shop-product-list 功能上下文 8.1.3）
-- ============================================
CREATE TABLE IF NOT EXISTS tb_processed_event (
    order_id    INTEGER PRIMARY KEY,               -- 已处理的订单ID（PAY_SUCCESS 事件）
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
);


-- ============================================
--  初始化测试类目（可选，用于测试）
-- ============================================
INSERT INTO tb_category (shop_id, name, parent_id, sort, status)
SELECT 1, '默认类目', 0, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM tb_category WHERE shop_id = 1 AND name = '默认类目');
