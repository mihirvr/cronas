-- 1. Create the core jobs table
CREATE TABLE jobs (
    job_id VARCHAR(36) PRIMARY KEY,
    target_url VARCHAR(2048) NOT NULL,
    http_method VARCHAR(10) NOT NULL,
    headers TEXT,
    payload TEXT,
    state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    scheduled_time TIMESTAMPTZ NOT NULL,
    max_retries INT NOT NULL DEFAULT 3,
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    CONSTRAINT chk_job_state CHECK (state IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED'))
);

-- 2. High-Performance Composite Index for the Polling Loop
CREATE INDEX idx_jobs_state_scheduled_time ON jobs (state, scheduled_time);