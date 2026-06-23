-- ============================================================
-- V9: Fix constraints, indexes, and sequences for fresh database
-- ============================================================

-- 1. CRITICAL: Sync sequences after seed data with explicit IDs
--    Without this, the next auto-generated ID would collide with seeded rows.
SELECT setval('tenants_id_seq',  (SELECT COALESCE(MAX(id), 1) FROM tenants));
SELECT setval('users_id_seq',    (SELECT COALESCE(MAX(id), 1) FROM users));

-- 2. Fix data_sources status CHECK constraint to include 'DELETED'
--    The Java enum DataSource.Status has DELETED but the original CHECK omitted it.
ALTER TABLE data_sources DROP CONSTRAINT IF EXISTS data_sources_status_check;
ALTER TABLE data_sources ADD CONSTRAINT data_sources_status_check
    CHECK (status IN ('ACTIVE','INACTIVE','ERROR','DELETED'));

-- 3. Expression index for LOWER(status) on orders
--    Many queries use LOWER(o.status) = ... or LOWER(o.status) IN ...
--    Without this, the existing idx_orders_tenant_status_date index cannot be used.
CREATE INDEX IF NOT EXISTS idx_orders_tenant_lower_status_date
    ON orders (tenant_id, LOWER(status), created_at_external);

-- 4. Expression index for LOWER(status) on orders (without date, for simple status lookups)
CREATE INDEX IF NOT EXISTS idx_orders_tenant_lower_status
    ON orders (tenant_id, LOWER(status));

-- 5. Covering index for revenue/shipping_fee SUM queries with valid statuses + date range
--    Covers: sumValidRevenueByTenantIdAndDateRange, sumValidShippingFeeByTenantIdAndDateRange,
--            countValidOrdersByTenantIdAndDateRange, countDistinctPhonesByTenantIdAndDateRange
CREATE INDEX IF NOT EXISTS idx_orders_tenant_date_lower_status_revenue_ship
    ON orders (tenant_id, created_at_external, LOWER(status))
    INCLUDE (revenue, shipping_fee, customer_phone);

-- 6. Index for customer_phone distinct count queries (SĐT liên hệ)
CREATE INDEX IF NOT EXISTS idx_orders_tenant_phone_lower_status_date
    ON orders (tenant_id, customer_phone, LOWER(status), created_at_external);

-- 7. Index for order_attribution join/count queries
CREATE INDEX IF NOT EXISTS idx_order_attribution_order_id
    ON order_attribution (order_id);

-- 8. Index for ads_metrics_daily message_contacts aggregation
CREATE INDEX IF NOT EXISTS idx_ads_metrics_tenant_date_message_contacts
    ON ads_metrics_daily (tenant_id, date)
    INCLUDE (message_contacts, spend, clicks, impressions);
