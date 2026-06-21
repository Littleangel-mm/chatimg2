-- 异步生图：为 generation_records 增加状态字段
-- 在已有数据库上执行一次即可

ALTER TABLE generation_records ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'processing';
ALTER TABLE generation_records ADD COLUMN IF NOT EXISTS error_message TEXT;

UPDATE generation_records SET status = 'completed' WHERE image_url IS NOT NULL AND (status IS NULL OR status = 'processing');
UPDATE generation_records SET status = 'processing' WHERE image_url IS NULL AND (status IS NULL OR status = '');
