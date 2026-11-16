-- Report paid Pancake orders back to Meta (Conversions API for business messaging).
ALTER TABLE orders ADD COLUMN IF NOT EXISTS fb_page_id VARCHAR(32);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS fb_psid VARCHAR(64);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS meta_purchase_sent_at TIMESTAMP;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS meta_purchase_error VARCHAR(500);

-- Backfill from the stored payload. Pancake Messenger conversations are "<pageId>_<psid>";
-- the customer's fb_id uses the same shape. raw_json may be truncated, so some rows stay null
-- until the next sync rewrites them from the full payload.
UPDATE orders o
SET fb_page_id = m.parts[1], fb_psid = m.parts[2]
FROM (
    SELECT id, COALESCE(
        regexp_match(raw_json, '"conversation_id":"([0-9]+)_([0-9]+)"'),
        regexp_match(raw_json, '"fb_id":"([0-9]+)_([0-9]+)"')) AS parts
    FROM orders
    WHERE fb_psid IS NULL
) m
WHERE o.id = m.id AND m.parts IS NOT NULL;
