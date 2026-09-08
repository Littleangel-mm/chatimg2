-- Additive migration; existing single-image records retain source_image_path.
ALTER TABLE generation_records ADD COLUMN IF NOT EXISTS source_image_paths TEXT;
