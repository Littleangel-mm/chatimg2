CREATE TABLE video_generation_tasks (
    id BIGSERIAL PRIMARY KEY,
    task_code VARCHAR(40) NOT NULL,
    provider VARCHAR(32) NOT NULL DEFAULT 'BAGEGE',
    provider_task_id VARCHAR(160),
    client_message_id VARCHAR(120),
    user_id INTEGER,
    key_id INTEGER,
    user_email VARCHAR(120),
    model VARCHAR(120) NOT NULL,
    generation_type VARCHAR(32) NOT NULL,
    prompt TEXT NOT NULL,
    duration_seconds INTEGER NOT NULL,
    aspect_ratio VARCHAR(20) NOT NULL,
    resolution VARCHAR(20) NOT NULL,
    status VARCHAR(32) NOT NULL,
    provider_status VARCHAR(32),
    billing_status VARCHAR(20) NOT NULL,
    quoted_unit_cost NUMERIC(12, 4),
    reserved_credits INTEGER NOT NULL DEFAULT 0,
    provider_cost NUMERIC(12, 4),
    charged_credits INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(120),
    error_message TEXT,
    error_reason TEXT,
    error_suggestion TEXT,
    request_payload JSONB,
    provider_response JSONB,
    next_poll_at TIMESTAMP,
    last_polled_at TIMESTAMP,
    submitted_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_video_generation_tasks_task_code UNIQUE (task_code),
    CONSTRAINT uk_video_generation_tasks_provider_task UNIQUE (provider_task_id),
    CONSTRAINT chk_video_generation_tasks_owner CHECK (user_id IS NOT NULL OR key_id IS NOT NULL),
    CONSTRAINT chk_video_generation_tasks_duration CHECK (duration_seconds BETWEEN 1 AND 15),
    CONSTRAINT chk_video_generation_tasks_reserved CHECK (reserved_credits >= 0),
    CONSTRAINT chk_video_generation_tasks_charged CHECK (charged_credits >= 0),
    CONSTRAINT chk_video_generation_tasks_status CHECK (status IN (
        'SUBMITTING', 'PENDING', 'PROCESSING', 'SUBMISSION_UNKNOWN',
        'POLLING_DELAYED', 'COMPLETED', 'FAILED'
    )),
    CONSTRAINT chk_video_generation_tasks_type CHECK (generation_type IN (
        'TEXT_TO_VIDEO', 'IMAGE_TO_VIDEO', 'VIDEO_TO_VIDEO'
    )),
    CONSTRAINT chk_video_generation_tasks_billing CHECK (billing_status IN (
        'RESERVED', 'SETTLED', 'REFUNDED'
    ))
);

CREATE UNIQUE INDEX uk_video_generation_tasks_user_message
    ON video_generation_tasks(user_id, client_message_id)
    WHERE user_id IS NOT NULL AND client_message_id IS NOT NULL;

CREATE UNIQUE INDEX uk_video_generation_tasks_key_message
    ON video_generation_tasks(key_id, client_message_id)
    WHERE key_id IS NOT NULL AND client_message_id IS NOT NULL;

CREATE INDEX idx_video_generation_tasks_user_created
    ON video_generation_tasks(user_id, created_at DESC);

CREATE INDEX idx_video_generation_tasks_key_created
    ON video_generation_tasks(key_id, created_at DESC);

CREATE INDEX idx_video_generation_tasks_polling
    ON video_generation_tasks(status, next_poll_at)
    WHERE status IN ('PENDING', 'PROCESSING', 'SUBMISSION_UNKNOWN', 'POLLING_DELAYED');

CREATE INDEX idx_video_generation_tasks_provider_status
    ON video_generation_tasks(provider_status, last_polled_at);

CREATE TABLE video_generation_assets (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL REFERENCES video_generation_tasks(id) ON DELETE CASCADE,
    direction VARCHAR(16) NOT NULL,
    media_type VARCHAR(16) NOT NULL,
    asset_role VARCHAR(20) NOT NULL,
    source_url TEXT,
    local_path VARCHAR(1000),
    provider_url TEXT,
    provider_asset_id VARCHAR(255),
    original_filename VARCHAR(255),
    mime_type VARCHAR(120),
    file_size BIGINT,
    checksum_sha256 VARCHAR(64),
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_video_generation_assets_direction CHECK (direction IN ('INPUT', 'OUTPUT')),
    CONSTRAINT chk_video_generation_assets_media CHECK (media_type IN ('IMAGE', 'VIDEO', 'AUDIO')),
    CONSTRAINT chk_video_generation_assets_role CHECK (asset_role IN (
        'SUBJECT', 'BACKGROUND', 'REFERENCE', 'SOURCE', 'RESULT'
    )),
    CONSTRAINT chk_video_generation_assets_size CHECK (file_size IS NULL OR file_size >= 0),
    CONSTRAINT chk_video_generation_assets_order CHECK (sort_order >= 0)
);

CREATE INDEX idx_video_generation_assets_task_order
    ON video_generation_assets(task_id, direction, sort_order);

CREATE INDEX idx_video_generation_assets_checksum
    ON video_generation_assets(checksum_sha256)
    WHERE checksum_sha256 IS NOT NULL;

CREATE TABLE video_task_events (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL REFERENCES video_generation_tasks(id) ON DELETE CASCADE,
    event_type VARCHAR(32) NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32),
    provider_http_status INTEGER,
    provider_error_code VARCHAR(120),
    retryable BOOLEAN,
    message TEXT,
    payload JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_video_task_events_type CHECK (event_type IN (
        'CREATED', 'SUBMITTED', 'STATUS_CHANGED', 'PROVIDER_ERROR',
        'POLL_DELAYED', 'RECOVERED', 'COMPLETED', 'FAILED'
    ))
);

CREATE INDEX idx_video_task_events_task_created
    ON video_task_events(task_id, created_at DESC);

COMMENT ON TABLE video_generation_tasks IS 'Local source of truth for asynchronous video generation tasks';
COMMENT ON COLUMN video_generation_tasks.provider_task_id IS 'Provider job id returned by POST /videos/generations';
COMMENT ON COLUMN video_generation_tasks.status IS 'Local task state; delayed and unknown states are non-terminal';
COMMENT ON COLUMN video_generation_tasks.provider_cost IS 'Actual cost reported by the provider, separate from user credits';
COMMENT ON TABLE video_generation_assets IS 'Input references and generated output media for a video task';
COMMENT ON TABLE video_task_events IS 'State transitions and exceptional provider interactions; normal polls are not recorded';
