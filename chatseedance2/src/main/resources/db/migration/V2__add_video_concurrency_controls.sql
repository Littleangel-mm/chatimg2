ALTER TABLE video_generation_tasks
    ADD COLUMN request_fingerprint VARCHAR(64),
    ADD COLUMN submit_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN poll_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_state_changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE video_generation_tasks
    ADD CONSTRAINT chk_video_generation_tasks_submit_attempts CHECK (submit_attempts >= 0),
    ADD CONSTRAINT chk_video_generation_tasks_poll_attempts CHECK (poll_attempts >= 0);

CREATE INDEX idx_video_generation_tasks_user_fingerprint
    ON video_generation_tasks(user_id, request_fingerprint, created_at DESC)
    WHERE user_id IS NOT NULL AND request_fingerprint IS NOT NULL;

CREATE INDEX idx_video_generation_tasks_key_fingerprint
    ON video_generation_tasks(key_id, request_fingerprint, created_at DESC)
    WHERE key_id IS NOT NULL AND request_fingerprint IS NOT NULL;

CREATE TABLE IF NOT EXISTS distributed_locks (
    lock_key VARCHAR(160) PRIMARY KEY,
    owner_id VARCHAR(160) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_distributed_locks_expires_at
    ON distributed_locks(expires_at);

COMMENT ON COLUMN video_generation_tasks.request_fingerprint IS
    'SHA-256 of normalized generation inputs for server-side debounce';
COMMENT ON COLUMN video_generation_tasks.submitted_at IS
    'Set before the provider POST starts; a missing provider id after this point is submission-unknown';
