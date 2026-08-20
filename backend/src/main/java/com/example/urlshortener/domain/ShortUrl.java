package com.example.urlshortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.time.ZoneOffset;

@Entity
@Table(name = "short_urls", uniqueConstraints = {
        @UniqueConstraint(name = "uk_short_urls_short_code", columnNames = "short_code"),
        @UniqueConstraint(name = "uk_short_urls_original_url_hash", columnNames = "original_url_hash")
}, indexes = {
        @Index(name = "idx_short_urls_active_expires_at", columnList = "active, expires_at")
})
public class ShortUrl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_code", nullable = false, length = 6, unique = true)
    private String shortCode;

    @Column(name = "short_url", nullable = false, length = 512)
    private String shortUrl;

    @Column(name = "original_url", nullable = false, length = 2048)
    private String originalUrl;

    @Column(name = "original_url_hash", length = 64, unique = true)
    private String originalUrlHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "click_count", nullable = false)
    private long clickCount;

    @Column(name = "last_accessed_timestamp", nullable = false)
    private Instant lastAccessedTimestamp;

    @Column(name = "created_timestamp", nullable = false, updatable = false)
    private Instant createdTimestamp;

    protected ShortUrl() {
    }

    public ShortUrl(
            String shortCode,
            String shortUrl,
            String originalUrl,
            String originalUrlHash,
            Instant createdTimestamp) {
        this.shortCode = shortCode;
        this.shortUrl = shortUrl;
        this.originalUrl = originalUrl;
        this.originalUrlHash = originalUrlHash;
        this.expiresAt = createdTimestamp.atZone(ZoneOffset.UTC)
                .plusMonths(1)
                .toInstant();
        this.active = true;
        this.clickCount = 0;
        this.lastAccessedTimestamp = createdTimestamp;
        this.createdTimestamp = createdTimestamp;
    }

    public Long getId() {
        return id;
    }

    public String getShortCode() {
        return shortCode;
    }

    public String getShortUrl() {
        return shortUrl;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public String getOriginalUrlHash() {
        return originalUrlHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isActive() {
        return active;
    }

    public long getClickCount() {
        return clickCount;
    }

    public Instant getLastAccessedTimestamp() {
        return lastAccessedTimestamp;
    }

    public void deactivate() {
        this.active = false;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getCreatedTimestamp() {
        return createdTimestamp;
    }
}
