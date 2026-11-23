-- Phones (or any small device) that each watch one ad account and post alerts/analyses back.
-- A worker authenticates with its own key; only the SHA-256 of the key is stored.
CREATE TABLE ad_workers (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      BIGINT       NOT NULL,
    name           VARCHAR(100) NOT NULL,
    ad_account_id  BIGINT       NOT NULL,
    key_hash       VARCHAR(64)  NOT NULL UNIQUE,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    device         VARCHAR(200),
    last_seen_at   TIMESTAMP,
    last_status    VARCHAR(500),
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- What a worker reports: ALERT (a rule was hit) or ANALYSIS (an AI write-up). ref_key de-duplicates alerts.
CREATE TABLE ad_worker_reports (
    id          BIGSERIAL PRIMARY KEY,
    worker_id   BIGINT       NOT NULL REFERENCES ad_workers(id) ON DELETE CASCADE,
    kind        VARCHAR(20)  NOT NULL,
    severity    VARCHAR(10),
    ref_key     VARCHAR(200),
    title       VARCHAR(300) NOT NULL,
    body        TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ad_worker_reports_worker_time ON ad_worker_reports (worker_id, created_at DESC);
CREATE UNIQUE INDEX uq_ad_worker_reports_ref ON ad_worker_reports (worker_id, ref_key) WHERE ref_key IS NOT NULL;
