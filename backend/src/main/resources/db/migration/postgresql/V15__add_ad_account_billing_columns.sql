ALTER TABLE ad_accounts ADD COLUMN IF NOT EXISTS billing_threshold NUMERIC(18,2);
ALTER TABLE ad_accounts ADD COLUMN IF NOT EXISTS next_bill_date DATE;
