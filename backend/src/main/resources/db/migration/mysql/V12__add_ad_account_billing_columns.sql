ALTER TABLE ad_accounts ADD COLUMN billing_threshold DECIMAL(18,2) NULL;
ALTER TABLE ad_accounts ADD COLUMN next_bill_date DATE NULL;
