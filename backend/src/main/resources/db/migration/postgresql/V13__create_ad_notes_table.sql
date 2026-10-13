CREATE TABLE IF NOT EXISTS ad_notes (
    id         BIGSERIAL PRIMARY KEY,
    tenant_id  BIGINT NOT NULL REFERENCES tenants(id),
    ad_id      VARCHAR(100) NOT NULL,
    content    TEXT NOT NULL,
    author     VARCHAR(100),
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ad_notes_tenant_ad ON ad_notes (tenant_id, ad_id);
