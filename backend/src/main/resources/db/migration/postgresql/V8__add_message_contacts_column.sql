ALTER TABLE ads_metrics_daily ADD COLUMN IF NOT EXISTS message_contacts BIGINT DEFAULT 0;
