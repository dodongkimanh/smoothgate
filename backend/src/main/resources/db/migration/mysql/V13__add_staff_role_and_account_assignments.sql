-- Staff users only see the ad accounts an admin assigns to them
ALTER TABLE users MODIFY role ENUM('ADMIN','ANALYST','STAFF') NOT NULL DEFAULT 'ADMIN';

-- Self-registered workspace owners were created as ANALYST; they are the tenant admins
UPDATE users SET role = 'ADMIN' WHERE role = 'ANALYST';

CREATE TABLE user_ad_accounts (
    user_id       BIGINT NOT NULL,
    ad_account_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, ad_account_id),
    CONSTRAINT fk_uaa_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_uaa_ad_account FOREIGN KEY (ad_account_id) REFERENCES ad_accounts(id) ON DELETE CASCADE
);
