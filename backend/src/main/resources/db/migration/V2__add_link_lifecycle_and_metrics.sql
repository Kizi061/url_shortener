ALTER TABLE short_urls
    MODIFY COLUMN short_code VARCHAR(6) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    MODIFY COLUMN original_url_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN expires_at DATETIME(6) NULL AFTER original_url_hash,
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE AFTER expires_at,
    ADD COLUMN click_count BIGINT NOT NULL DEFAULT 0 AFTER active,
    ADD COLUMN last_accessed_timestamp DATETIME(6) NULL AFTER click_count,
    ADD CONSTRAINT ck_short_urls_click_count_nonnegative CHECK (click_count >= 0);

CREATE INDEX idx_short_urls_active_expires_at
    ON short_urls (active, expires_at);
