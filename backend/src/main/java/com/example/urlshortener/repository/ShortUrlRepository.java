package com.example.urlshortener.repository;

import com.example.urlshortener.domain.ShortUrl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {
    boolean existsByShortCode(String shortCode);
    Optional<ShortUrl> findByShortCode(String shortCode);
    Optional<ShortUrl> findByOriginalUrlHash(String originalUrlHash);
    Optional<ShortUrl> findByOriginalUrl(String originalUrl);

    @Query("""
            select shortUrl
            from ShortUrl shortUrl
            where shortUrl.shortCode = :shortCode
              and shortUrl.active = true
              and (shortUrl.expiresAt is null or shortUrl.expiresAt > :accessedAt)
            """)
    Optional<ShortUrl> findRedirectCandidate(
            @Param("shortCode") String shortCode,
            @Param("accessedAt") Instant accessedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update ShortUrl shortUrl
            set shortUrl.clickCount = shortUrl.clickCount + 1,
                shortUrl.lastAccessedTimestamp = :accessedAt
            where shortUrl.id = :id
              and shortUrl.active = true
              and (shortUrl.expiresAt is null or shortUrl.expiresAt > :accessedAt)
            """)
    int recordSuccessfulAccess(
            @Param("id") Long id,
            @Param("accessedAt") Instant accessedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update ShortUrl shortUrl
            set shortUrl.clickCount = shortUrl.clickCount + 1,
                shortUrl.lastAccessedTimestamp = :accessedAt
            where shortUrl.id = :id
            """)
    int recordExistingUrlAccess(
            @Param("id") Long id,
            @Param("accessedAt") Instant accessedAt);
}
