package com.example.urlshortener.service;

import com.example.urlshortener.config.UrlShortenerProperties;
import com.example.urlshortener.domain.ShortUrl;
import com.example.urlshortener.dto.ShortUrlResponse;
import com.example.urlshortener.exception.InvalidUrlException;
import com.example.urlshortener.exception.ShortCodeGenerationException;
import com.example.urlshortener.exception.ShortUrlNotFoundException;
import com.example.urlshortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShortUrlServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T18:30:00Z");

    @Mock
    private ShortUrlRepository repository;
    @Mock
    private ShortCodeGenerator generator;

    private ShortUrlService service;

    @BeforeEach
    void setUp() {
        service = new ShortUrlService(
                repository,
                generator,
                new OriginalUrlHasher(),
                new UrlValidator(),
                new UrlShortenerProperties("http://localhost:8080", "http://localhost:5173"),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsShortUrlSuccessfully() {
        String originalUrl = "https://www.example.com/products/category/item/12345";
        when(generator.nextCode()).thenReturn("aB12Cd");
        when(repository.existsByShortCode("aB12Cd")).thenReturn(false);
        when(repository.saveAndFlush(any(ShortUrl.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ShortUrlCreationResult result = service.createShortUrl(originalUrl);
        ShortUrlResponse response = result.response();

        assertThat(result.created()).isTrue();
        assertThat(response.shortCode()).isEqualTo("aB12Cd");
        assertThat(response.shortUrl()).isEqualTo("http://localhost:8080/aB12Cd");
        assertThat(response.originalUrl()).isEqualTo(originalUrl);
        verify(repository).saveAndFlush(any(ShortUrl.class));
    }

    @Test
    void returnsExistingMappingWithoutGeneratingOrSavingAnotherRecord() {
        String originalUrl = "https://example.com/already-shortened";
        String originalUrlHash = new OriginalUrlHasher().hash(originalUrl);
        ShortUrl existing = new ShortUrl(
                "old123",
                "http://localhost:8080/old123",
                originalUrl,
                originalUrlHash,
                NOW);
        when(repository.findByOriginalUrlHash(originalUrlHash)).thenReturn(Optional.of(existing));

        ShortUrlCreationResult result = service.createShortUrl(originalUrl);

        assertThat(result.created()).isFalse();
        assertThat(result.response().shortCode()).isEqualTo("old123");
        assertThat(result.response().shortUrl()).isEqualTo("http://localhost:8080/old123");
        verify(generator, never()).nextCode();
        verify(repository, never()).saveAndFlush(any(ShortUrl.class));
    }

    @Test
    void rejectsInvalidUrl() {
        assertThatThrownBy(() -> service.createShortUrl("example.com/no-scheme"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void looksUpOriginalUrlByShortCode() {
        ShortUrl entity = new ShortUrl(
                "aB12Cd",
                "http://localhost:8080/aB12Cd",
                "https://example.com/page",
                new OriginalUrlHasher().hash("https://example.com/page"),
                NOW);
        when(repository.findByShortCode("aB12Cd")).thenReturn(Optional.of(entity));

        assertThat(service.getOriginalUrl("aB12Cd")).isEqualTo("https://example.com/page");
    }

    @Test
    void throwsNotFoundForUnknownShortCode() {
        when(repository.findByShortCode("xxxxxx")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOriginalUrl("xxxxxx"))
                .isInstanceOf(ShortUrlNotFoundException.class);
    }

    @Test
    void retriesWhenGeneratedCodeAlreadyExists() {
        when(generator.nextCode()).thenReturn("taken1", "fresh2");
        when(repository.existsByShortCode("taken1")).thenReturn(true);
        when(repository.existsByShortCode("fresh2")).thenReturn(false);
        when(repository.saveAndFlush(any(ShortUrl.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ShortUrlResponse response = service.createShortUrl("https://example.com").response();

        assertThat(response.shortCode()).isEqualTo("fresh2");
    }

    @Test
    void retriesWhenConcurrentInsertCausesUniqueConstraintViolation() {
        when(generator.nextCode()).thenReturn("race01", "safe02");
        when(repository.existsByShortCode("race01")).thenReturn(false);
        when(repository.existsByShortCode("safe02")).thenReturn(false);
        when(repository.saveAndFlush(any(ShortUrl.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ShortUrlResponse response = service.createShortUrl("https://example.com").response();

        assertThat(response.shortCode()).isEqualTo("safe02");
    }

    @Test
    void returnsConcurrentlyCreatedMappingWhenOriginalUrlUniqueConstraintWins() {
        String originalUrl = "https://example.com/concurrent";
        String originalUrlHash = new OriginalUrlHasher().hash(originalUrl);
        ShortUrl concurrentlyCreated = new ShortUrl(
                "other1",
                "http://localhost:8080/other1",
                originalUrl,
                originalUrlHash,
                NOW);
        when(repository.findByOriginalUrlHash(originalUrlHash))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(concurrentlyCreated));
        when(generator.nextCode()).thenReturn("mine01");
        when(repository.existsByShortCode("mine01")).thenReturn(false);
        when(repository.saveAndFlush(any(ShortUrl.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate original URL"));

        ShortUrlCreationResult result = service.createShortUrl(originalUrl);

        assertThat(result.created()).isFalse();
        assertThat(result.response().shortCode()).isEqualTo("other1");
    }

    @Test
    void failsWithConflictAfterBoundedCollisionRetries() {
        when(generator.nextCode()).thenReturn("taken1");
        when(repository.existsByShortCode("taken1")).thenReturn(true);

        assertThatThrownBy(() -> service.createShortUrl("https://example.com"))
                .isInstanceOf(ShortCodeGenerationException.class);
    }
}
