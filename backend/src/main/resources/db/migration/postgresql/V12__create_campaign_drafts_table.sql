CREATE TABLE IF NOT EXISTS campaign_drafts (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT NOT NULL REFERENCES tenants(id),
    name         VARCHAR(255) NOT NULL,
    objective    VARCHAR(100),
    status       VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    payload_json JSONB,
    created_at   TIMESTAMP DEFAULT NOW(),
    updated_at   TIMESTAMP DEFAULT NOW()
);
