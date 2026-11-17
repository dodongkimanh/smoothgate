-- What Meta reports for each synced audience: matched size range and processing / delivery status.
ALTER TABLE meta_customer_audiences ADD COLUMN IF NOT EXISTS meta_count_lower BIGINT;
ALTER TABLE meta_customer_audiences ADD COLUMN IF NOT EXISTS meta_count_upper BIGINT;
ALTER TABLE meta_customer_audiences ADD COLUMN IF NOT EXISTS meta_status VARCHAR(500);
ALTER TABLE meta_customer_audiences ADD COLUMN IF NOT EXISTS meta_checked_at TIMESTAMP;
