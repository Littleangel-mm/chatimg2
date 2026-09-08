-- ChatImg2 database init script
-- Database: chatimg2 (PostgreSQL)

CREATE TABLE IF NOT EXISTS admins (
    id SERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS activation_keys (
    id SERIAL PRIMARY KEY,
    key_code VARCHAR(64) NOT NULL UNIQUE,
    total_credits INTEGER NOT NULL DEFAULT 100,
    used_credits INTEGER NOT NULL DEFAULT 0,
    status SMALLINT NOT NULL DEFAULT 1,
    user_id INTEGER,
    user_email VARCHAR(120),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS generation_records (
    id SERIAL PRIMARY KEY,
    key_id INTEGER REFERENCES activation_keys(id) ON DELETE CASCADE,
    user_id INTEGER,
    user_email VARCHAR(120),
    task_code VARCHAR(32) NOT NULL UNIQUE,
    prompt TEXT NOT NULL,
    image_url VARCHAR(500),
    local_path VARCHAR(500),
    generation_type VARCHAR(20) DEFAULT 'text2img',
    source_image_path VARCHAR(500),
    source_image_paths TEXT,
    credits_cost INTEGER NOT NULL DEFAULT 20,
    status VARCHAR(20) DEFAULT 'processing',
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

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

CREATE INDEX IF NOT EXISTS idx_generation_records_key_id ON generation_records(key_id);
CREATE INDEX IF NOT EXISTS idx_generation_records_task_code ON generation_records(task_code);
CREATE INDEX IF NOT EXISTS idx_generation_records_user_id ON generation_records(user_id);
CREATE INDEX IF NOT EXISTS idx_generation_records_user_email ON generation_records(user_email);
CREATE INDEX IF NOT EXISTS idx_activation_keys_key_code ON activation_keys(key_code);
CREATE INDEX IF NOT EXISTS idx_activation_keys_user_id ON activation_keys(user_id);
CREATE INDEX IF NOT EXISTS idx_activation_keys_user_email ON activation_keys(user_email);
CREATE INDEX IF NOT EXISTS idx_inspiration_media_type ON inspiration_prompts(media_type);

INSERT INTO admins (username, password)
VALUES ('admin', '240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9')
ON CONFLICT (username) DO NOTHING;
