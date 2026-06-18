-- ChatImg2 数据库初始化脚本
-- 数据库: chatimg2 (PostgreSQL)

-- 管理员表
CREATE TABLE IF NOT EXISTS admins (
    id SERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 密钥表（管理员发放，用户激活使用）
CREATE TABLE IF NOT EXISTS activation_keys (
    id SERIAL PRIMARY KEY,
    key_code VARCHAR(64) NOT NULL UNIQUE,       -- 密钥编号
    total_credits INTEGER NOT NULL DEFAULT 100,  -- 总积分（默认100）
    used_credits INTEGER NOT NULL DEFAULT 0,     -- 已使用积分
    status SMALLINT NOT NULL DEFAULT 1,          -- 状态: 1=启用, 0=禁用
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 图片生成记录表
CREATE TABLE IF NOT EXISTS generation_records (
    id SERIAL PRIMARY KEY,
    key_id INTEGER NOT NULL REFERENCES activation_keys(id) ON DELETE CASCADE,
    prompt TEXT NOT NULL,                         -- 用户提示词
    image_url TEXT,                               -- API返回的图片链接
    local_path VARCHAR(500),                     -- 本地备份路径
    generation_type VARCHAR(20) DEFAULT 'text2img',
    source_image_path VARCHAR(500),
    credits_cost INTEGER NOT NULL DEFAULT 20,     -- 消耗积分（默认20）
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_generation_records_key_id ON generation_records(key_id);
CREATE INDEX IF NOT EXISTS idx_activation_keys_key_code ON activation_keys(key_code);

-- 默认管理员 (密码: admin123, SHA256加密)
INSERT INTO admins (username, password)
VALUES ('admin', '240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9')
ON CONFLICT (username) DO NOTHING;
