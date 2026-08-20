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

@Service
public class ShortUrlService {

    static final int MAX_GENERATION_ATTEMPTS = 10;

    private final ShortUrlRepository repository;
    private final ShortCodeGenerator shortCodeGenerator;
    private final UrlValidator urlValidator;
    private final UrlShortenerProperties properties;
    private final Clock clock;

    public ShortUrlService(
            ShortUrlRepository repository,
            ShortCodeGenerator shortCodeGenerator,
            UrlValidator urlValidator,
            UrlShortenerProperties properties,
            Clock clock) {
        this.repository = repository;
        this.shortCodeGenerator = shortCodeGenerator;
        this.urlValidator = urlValidator;
        this.properties = properties;
        this.clock = clock;
    }

    public ShortUrlResponse createShortUrl(String originalUrl) {
        urlValidator.validate(originalUrl);

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
                    Instant.now(clock));

            try {
                ShortUrl saved = repository.saveAndFlush(entity);
                return toResponse(saved);
            } catch (DataIntegrityViolationException exception) {
                // A concurrent request may have persisted the same generated code.
            }
        }

        throw new ShortCodeGenerationException();
    }

    public String getOriginalUrl(String shortCode) {
        return repository.findByShortCode(shortCode)
                .map(ShortUrl::getOriginalUrl)
                .orElseThrow(() -> new ShortUrlNotFoundException(shortCode));
    }

    private ShortUrlResponse toResponse(ShortUrl entity) {
        return new ShortUrlResponse(
                entity.getShortCode(),
                entity.getShortUrl(),
                entity.getOriginalUrl());
    }
}
