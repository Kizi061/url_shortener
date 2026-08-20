CREATE TABLE short_urls (
    id BIGINT NOT NULL AUTO_INCREMENT,
    short_code VARCHAR(6) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    short_url VARCHAR(512) NOT NULL,
    original_url VARCHAR(2048) NOT NULL,
    original_url_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_timestamp DATETIME(6) NOT NULL,
    CONSTRAINT pk_short_urls PRIMARY KEY (id),
    CONSTRAINT uk_short_urls_short_code UNIQUE (short_code),
    CONSTRAINT uk_short_urls_original_url_hash UNIQUE (original_url_hash)
) ENGINE=InnoDB;
