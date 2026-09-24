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
--  商品 SKU 表
-- ============================================
CREATE TABLE IF NOT EXISTS tb_sku (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    shop_id     INTEGER NOT NULL,                  -- 所属店铺
    category_id INTEGER NOT NULL,                  -- 类目ID
    name        VARCHAR(100) NOT NULL,             -- 商品名称
    description TEXT,                              -- 商品描述
    price       DECIMAL(10,2) NOT NULL,            -- 价格
    stock       INTEGER,                           -- 库存（虚拟商品可为空=无限库存）
    type        TINYINT NOT NULL,                  -- 1-实物 2-虚拟
    images      VARCHAR(1000),                     -- 图片URL，逗号分隔
    spec        VARCHAR(500),                      -- 规格参数
    status      TINYINT DEFAULT 0,                 -- 0-下架 1-上架
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sku_shop_id ON tb_sku (shop_id);
CREATE INDEX idx_sku_category_id ON tb_sku (category_id);


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
--  初始化测试类目（可选，用于测试）
-- ============================================
INSERT INTO tb_category (shop_id, name, parent_id, sort, status)
SELECT 1, '默认类目', 0, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM tb_category WHERE shop_id = 1 AND name = '默认类目');
