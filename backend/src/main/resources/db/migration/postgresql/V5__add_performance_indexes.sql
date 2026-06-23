-- Performance indexes for production workloads
-- Safe to rerun across environments

SET statement_timeout = 0;

CREATE INDEX IF NOT EXISTS idx_tenants_name ON tenants (name);

CREATE INDEX IF NOT EXISTS idx_users_tenant_id ON users (tenant_id);
CREATE INDEX IF NOT EXISTS idx_users_tenant_role ON users (tenant_id, role);
CREATE INDEX IF NOT EXISTS idx_users_tenant_status ON users (tenant_id, status);

CREATE INDEX IF NOT EXISTS idx_data_sources_tenant_type_status ON data_sources (tenant_id, type, status);
CREATE INDEX IF NOT EXISTS idx_data_sources_tenant_status ON data_sources (tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_data_sources_tenant_created_at ON data_sources (tenant_id, created_at);

CREATE INDEX IF NOT EXISTS idx_sync_state_data_source_entity ON sync_state (data_source_id, entity);
CREATE INDEX IF NOT EXISTS idx_sync_state_tenant_updated_at ON sync_state (tenant_id, updated_at);

CREATE INDEX IF NOT EXISTS idx_pos_shops_tenant_data_source ON pos_shops (tenant_id, data_source_id);

CREATE INDEX IF NOT EXISTS idx_orders_tenant_id_id ON orders (tenant_id, id);
CREATE INDEX IF NOT EXISTS idx_orders_tenant_status_date ON orders (tenant_id, status, created_at_external);
CREATE INDEX IF NOT EXISTS idx_orders_tenant_phone_date ON orders (tenant_id, customer_phone, created_at_external);
CREATE INDEX IF NOT EXISTS idx_orders_tenant_updated_external ON orders (tenant_id, updated_at_external);

CREATE INDEX IF NOT EXISTS idx_ad_accounts_tenant_data_source ON ad_accounts (tenant_id, data_source_id);
CREATE INDEX IF NOT EXISTS idx_ad_accounts_tenant_platform ON ad_accounts (tenant_id, platform);

CREATE INDEX IF NOT EXISTS idx_campaigns_tenant_ad_account ON campaigns (tenant_id, ad_account_id);
CREATE INDEX IF NOT EXISTS idx_campaigns_tenant_status ON campaigns (tenant_id, status);

CREATE INDEX IF NOT EXISTS idx_ads_metrics_tenant_campaign_date ON ads_metrics_daily (tenant_id, campaign_id, date);
CREATE INDEX IF NOT EXISTS idx_ads_metrics_tenant_ad_account_date ON ads_metrics_daily (tenant_id, ad_account_id, date);
CREATE INDEX IF NOT EXISTS idx_ads_metrics_tenant_platform_date ON ads_metrics_daily (tenant_id, platform, date);

CREATE INDEX IF NOT EXISTS idx_order_attribution_tenant_campaign_match ON order_attribution (tenant_id, campaign_id, match_type);
CREATE INDEX IF NOT EXISTS idx_order_attribution_tenant_matched_at ON order_attribution (tenant_id, matched_at);

CREATE INDEX IF NOT EXISTS idx_campaign_perf_tenant_campaign_date ON campaign_perf_daily (tenant_id, campaign_id, date);

CREATE INDEX IF NOT EXISTS idx_system_settings_updated_at ON system_settings (updated_at);

SET statement_timeout = DEFAULT;
