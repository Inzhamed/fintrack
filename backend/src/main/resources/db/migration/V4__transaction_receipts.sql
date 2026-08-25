-- Receipt attachments.
--
-- Only the object key is stored, never the bytes. Keeping images in Postgres would bloat
-- every backup and every replica with data that is written once and read rarely, and is
-- exactly what object storage is for.

ALTER TABLE transactions
    ADD COLUMN receipt_key          VARCHAR(255),
    ADD COLUMN receipt_filename     VARCHAR(255),
    ADD COLUMN receipt_content_type VARCHAR(100),
    ADD COLUMN receipt_size_bytes   BIGINT;

-- Either every receipt column is set or none is. A half-populated row would mean an object
-- that exists with no way to name it, or a name pointing at nothing.
ALTER TABLE transactions
    ADD CONSTRAINT transactions_receipt_complete CHECK (
        (receipt_key IS NULL AND receipt_filename IS NULL
             AND receipt_content_type IS NULL AND receipt_size_bytes IS NULL)
        OR (receipt_key IS NOT NULL AND receipt_filename IS NOT NULL
             AND receipt_content_type IS NOT NULL AND receipt_size_bytes IS NOT NULL));

-- One object per transaction: re-uploading replaces rather than accumulating orphans.
CREATE UNIQUE INDEX transactions_receipt_key_key
    ON transactions (receipt_key) WHERE receipt_key IS NOT NULL;
