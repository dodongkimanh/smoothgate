CREATE TABLE IF NOT EXISTS ad_saved_views (
    id         BIGSERIAL PRIMARY KEY,
    tenant_id  BIGINT NOT NULL REFERENCES tenants(id),
    name       VARCHAR(255) NOT NULL,
    ad_keys    JSONB NOT NULL,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ad_saved_views_tenant ON ad_saved_views (tenant_id);
