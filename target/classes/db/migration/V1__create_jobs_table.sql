CREATE TABLE jobs (
    id BIGSERIAL PRIMARY KEY,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    priority INT NOT NULL DEFAULT 0,
    run_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    payload JSONB NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    idempotency_key VARCHAR(255) UNIQUE,
    locked_by VARCHAR(255),
    locked_until TIMESTAMP WITH TIME ZONE,
    last_error TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_jobs_status_run_at_priority ON jobs(status, run_at, priority DESC);
