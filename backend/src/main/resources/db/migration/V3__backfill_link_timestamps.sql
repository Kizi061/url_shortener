UPDATE short_urls
SET expires_at = created_timestamp + INTERVAL 1 MONTH
WHERE expires_at IS NULL;

UPDATE short_urls
SET last_accessed_timestamp = created_timestamp
WHERE last_accessed_timestamp IS NULL;

ALTER TABLE short_urls
    MODIFY COLUMN expires_at DATETIME(6) NOT NULL,
    MODIFY COLUMN last_accessed_timestamp DATETIME(6) NOT NULL;
