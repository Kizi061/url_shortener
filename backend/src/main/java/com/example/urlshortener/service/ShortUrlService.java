package com.example.urlshortener.service;

import com.example.urlshortener.config.UrlShortenerProperties;
import com.example.urlshortener.domain.ShortUrl;
import com.example.urlshortener.dto.ShortUrlResponse;
import com.example.urlshortener.exception.ShortCodeGenerationException;
import com.example.urlshortener.exception.ShortUrlNotFoundException;
import com.example.urlshortener.repository.ShortUrlRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
public class ShortUrlService {

    static final int MAX_GENERATION_ATTEMPTS = 10;

    private final ShortUrlRepository repository;
    private final ShortCodeGenerator shortCodeGenerator;
    private final OriginalUrlHasher originalUrlHasher;
    private final UrlValidator urlValidator;
    private final UrlShortenerProperties properties;
    private final Clock clock;

    public ShortUrlService(
            ShortUrlRepository repository,
            ShortCodeGenerator shortCodeGenerator,
            OriginalUrlHasher originalUrlHasher,
            UrlValidator urlValidator,
            UrlShortenerProperties properties,
            Clock clock) {
        this.repository = repository;
        this.shortCodeGenerator = shortCodeGenerator;
        this.originalUrlHasher = originalUrlHasher;
        this.urlValidator = urlValidator;
        this.properties = properties;
        this.clock = clock;
    }

    public ShortUrlCreationResult createShortUrl(String originalUrl) {
        urlValidator.validate(originalUrl);
        String originalUrlHash = originalUrlHasher.hash(originalUrl);
        Instant requestTimestamp = Instant.now(clock);

        Optional<ShortUrl> existing = findExisting(originalUrl, originalUrlHash);
        if (existing.isPresent()) {
            repository.recordExistingUrlAccess(existing.get().getId(), requestTimestamp);
            return new ShortUrlCreationResult(toResponse(existing.get()), false);
        }

        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String shortCode = shortCodeGenerator.nextCode();
            if (repository.existsByShortCode(shortCode)) {
                continue;
            }

            String shortUrlValue = properties.baseUrl() + "/" + shortCode;
            ShortUrl entity = new ShortUrl(
                    shortCode,
                    shortUrlValue,
                    originalUrl,
                    originalUrlHash,
                    requestTimestamp);

            try {
                ShortUrl saved = repository.saveAndFlush(entity);
                return new ShortUrlCreationResult(toResponse(saved), true);
            } catch (DataIntegrityViolationException exception) {
                Optional<ShortUrl> concurrentlyCreated = findExisting(originalUrl, originalUrlHash);
                if (concurrentlyCreated.isPresent()) {
                    repository.recordExistingUrlAccess(
                            concurrentlyCreated.get().getId(), requestTimestamp);
                    return new ShortUrlCreationResult(toResponse(concurrentlyCreated.get()), false);
                }
                // Otherwise, a concurrent request may have persisted the same short code.
            }
        }

        throw new ShortCodeGenerationException();
    }

    public String getOriginalUrl(String shortCode) {
        Instant accessedAt = Instant.now(clock);
        ShortUrl shortUrl = repository.findRedirectCandidate(shortCode, accessedAt)
                .orElseThrow(() -> new ShortUrlNotFoundException(shortCode));

        int updatedRows = repository.recordSuccessfulAccess(shortUrl.getId(), accessedAt);
        if (updatedRows == 0) {
            throw new ShortUrlNotFoundException(shortCode);
        }

        return shortUrl.getOriginalUrl();
    }

    private Optional<ShortUrl> findExisting(String originalUrl, String originalUrlHash) {
        Optional<ShortUrl> byHash = repository.findByOriginalUrlHash(originalUrlHash)
                .filter(shortUrl -> originalUrl.equals(shortUrl.getOriginalUrl()));
        if (byHash.isPresent()) {
            return byHash;
        }

        // Supports records created before the original_url_hash column was introduced.
        return repository.findByOriginalUrl(originalUrl);
    }

    private ShortUrlResponse toResponse(ShortUrl entity) {
        return new ShortUrlResponse(
                entity.getShortCode(),
                entity.getShortUrl(),
                entity.getOriginalUrl());
    }
}
