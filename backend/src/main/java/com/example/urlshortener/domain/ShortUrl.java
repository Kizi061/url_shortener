package com.example.urlshortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "short_urls", uniqueConstraints = {
        @UniqueConstraint(name = "uk_short_urls_short_code", columnNames = "short_code")
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

    @Column(name = "created_timestamp", nullable = false, updatable = false)
    private Instant createdTimestamp;

    protected ShortUrl() {
    }

    public ShortUrl(String shortCode, String shortUrl, String originalUrl, Instant createdTimestamp) {
        this.shortCode = shortCode;
        this.shortUrl = shortUrl;
        this.originalUrl = originalUrl;
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

    public Instant getCreatedTimestamp() {
        return createdTimestamp;
    }
}
