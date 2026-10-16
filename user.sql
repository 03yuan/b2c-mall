CREATE TABLE IF NOT EXISTS tb_user (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username VARCHAR(50) NOT NULL,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(50),
    avatar_url VARCHAR(500) DEFAULT '/avatars/default.png',
    status TINYINT DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_user_username ON tb_user(username);

INSERT INTO tb_user (username, password, nickname) VALUES
('test', '123456', '测试用户');
