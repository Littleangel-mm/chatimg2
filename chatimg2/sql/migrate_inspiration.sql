-- 灵感画廊 prompt 库：在已有数据库上执行一次即可
-- 应用启动时 DatabaseMigration 也会自动创建，此脚本用于手动迁移

CREATE TABLE IF NOT EXISTS inspiration_prompts (
    id SERIAL PRIMARY KEY,
    external_id VARCHAR(64) UNIQUE,
    media_type VARCHAR(16) NOT NULL DEFAULT 'image',
    category VARCHAR(100),
    subcategory VARCHAR(100),
    image_url VARCHAR(1000),
    source_url VARCHAR(1000),
    prompt TEXT NOT NULL,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_inspiration_media_type ON inspiration_prompts(media_type);
