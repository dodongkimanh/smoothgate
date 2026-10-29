-- Staff users only see the ad accounts an admin assigns to them
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('ADMIN', 'ANALYST', 'STAFF'));

-- Self-registered workspace owners were created as ANALYST; they are the tenant admins
UPDATE users SET role = 'ADMIN' WHERE role = 'ANALYST';

CREATE TABLE IF NOT EXISTS user_ad_accounts (
    user_id       BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ad_account_id BIGINT NOT NULL REFERENCES ad_accounts(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, ad_account_id)
);

CREATE INDEX IF NOT EXISTS idx_user_ad_accounts_ad_account ON user_ad_accounts (ad_account_id);
