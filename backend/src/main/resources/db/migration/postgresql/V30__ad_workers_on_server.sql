-- Workers run on the Smoothgate server (no phone): mode 'server'. A phone worker would be mode 'phone'.
ALTER TABLE ad_workers ADD COLUMN IF NOT EXISTS mode VARCHAR(16) NOT NULL DEFAULT 'server';

-- The server scheduler's bookkeeping per worker: last check, analysis slots and daily close done / tried.
CREATE TABLE IF NOT EXISTS ad_worker_runs (
    worker_id   BIGINT      NOT NULL REFERENCES ad_workers(id) ON DELETE CASCADE,
    run_key     VARCHAR(64) NOT NULL,
    tries       INT         NOT NULL DEFAULT 0,
    last_try_at TIMESTAMP,
    done_at     TIMESTAMP,
    PRIMARY KEY (worker_id, run_key)
);
