package com.example.urlshortener.service;

import com.example.urlshortener.config.UrlShortenerProperties;
import com.example.urlshortener.domain.ShortUrl;
import com.example.urlshortener.dto.ShortUrlAnalyticsResponse;
import com.example.urlshortener.dto.ShortUrlResponse;
import com.example.urlshortener.exception.InvalidUrlException;
import com.example.urlshortener.exception.ShortCodeGenerationException;
import com.example.urlshortener.exception.ShortUrlNotFoundException;
import com.example.urlshortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
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
        ArgumentCaptor<ShortUrl> entityCaptor = ArgumentCaptor.forClass(ShortUrl.class);
        verify(repository).saveAndFlush(entityCaptor.capture());
        ShortUrl saved = entityCaptor.getValue();
        assertThat(saved.getCreatedTimestamp()).isEqualTo(NOW);
        assertThat(saved.getLastAccessedTimestamp()).isEqualTo(NOW);
        assertThat(saved.getExpiresAt()).isEqualTo(Instant.parse("2026-09-19T18:30:00Z"));
        assertThat(saved.getClickCount()).isZero();
        assertThat(saved.isActive()).isTrue();
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
        verify(repository).recordExistingUrlAccess(null, NOW);
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
        when(repository.findRedirectCandidate("aB12Cd", NOW)).thenReturn(Optional.of(entity));
        when(repository.recordSuccessfulAccess(null, NOW)).thenReturn(1);

        assertThat(service.getOriginalUrl("aB12Cd")).isEqualTo("https://example.com/page");
        verify(repository).recordSuccessfulAccess(null, NOW);
    }

    @Test
    void returnsReadOnlyAnalyticsUsingExistingFieldSemantics() {
        ShortUrl entity = mock(ShortUrl.class);
        Instant lastActivity = NOW.minusSeconds(30);
        when(entity.getShortCode()).thenReturn("aB12Cd");
        when(entity.getClickCount()).thenReturn(7L);
        when(entity.getLastAccessedTimestamp()).thenReturn(lastActivity);
        when(repository.findRedirectCandidate("aB12Cd", NOW)).thenReturn(Optional.of(entity));

        ShortUrlAnalyticsResponse response = service.getAnalytics("aB12Cd");

        assertThat(response.shortCode()).isEqualTo("aB12Cd");
        assertThat(response.accessReuseCount()).isEqualTo(7);
        assertThat(response.lastRecordedActivityAt()).isEqualTo(lastActivity);
        assertThat(response.hasRecordedActivity()).isTrue();
        verify(repository, never()).recordSuccessfulAccess(any(), any());
        verify(repository, never()).recordExistingUrlAccess(any(), any());
    }

    @Test
    void reportsNoPostCreationActivityWhenCountIsZero() {
        ShortUrl entity = mock(ShortUrl.class);
        when(entity.getShortCode()).thenReturn("new123");
        when(entity.getClickCount()).thenReturn(0L);
        when(entity.getLastAccessedTimestamp()).thenReturn(NOW);
        when(repository.findRedirectCandidate("new123", NOW)).thenReturn(Optional.of(entity));

        ShortUrlAnalyticsResponse response = service.getAnalytics("new123");

        assertThat(response.hasRecordedActivity()).isFalse();
        assertThat(response.lastRecordedActivityAt()).isEqualTo(NOW);
    }

    @Test
    void throwsNotFoundWhenAnalyticsCodeIsNotEligible() {
        when(repository.findRedirectCandidate("hidden", NOW)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAnalytics("hidden"))
                .isInstanceOf(ShortUrlNotFoundException.class);
    }

    @Test
    void throwsNotFoundForUnknownShortCode() {
        when(repository.findRedirectCandidate("xxxxxx", NOW)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOriginalUrl("xxxxxx"))
                .isInstanceOf(ShortUrlNotFoundException.class);
    }

    @Test
    void rejectsRedirectWhenLinkChangesStateAfterLookup() {
        ShortUrl entity = new ShortUrl(
                "aB12Cd",
                "http://localhost:8080/aB12Cd",
                "https://example.com/page",
                new OriginalUrlHasher().hash("https://example.com/page"),
                NOW);
        when(repository.findRedirectCandidate("aB12Cd", NOW)).thenReturn(Optional.of(entity));
        when(repository.recordSuccessfulAccess(null, NOW)).thenReturn(0);

        assertThatThrownBy(() -> service.getOriginalUrl("aB12Cd"))
                .isInstanceOf(ShortUrlNotFoundException.class);
    }

    @Test
    void doesNotRedirectWhenClickCountUpdateFails() {
        ShortUrl entity = new ShortUrl(
                "aB12Cd",
                "http://localhost:8080/aB12Cd",
                "https://example.com/page",
                new OriginalUrlHasher().hash("https://example.com/page"),
                NOW);
        when(repository.findRedirectCandidate("aB12Cd", NOW)).thenReturn(Optional.of(entity));
        when(repository.recordSuccessfulAccess(null, NOW))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> service.getOriginalUrl("aB12Cd"))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void stopsCreationWhenInitialRepositoryLookupFails() {
        when(repository.findByOriginalUrlHash(anyString()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> service.createShortUrl("https://example.com"))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(generator, never()).nextCode();
        verify(repository, never()).saveAndFlush(any(ShortUrl.class));
    }

    @Test
    void doesNotRetryNonConstraintPersistenceFailure() {
        when(generator.nextCode()).thenReturn("fresh1");
        when(repository.existsByShortCode("fresh1")).thenReturn(false);
        when(repository.saveAndFlush(any(ShortUrl.class)))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> service.createShortUrl("https://example.com"))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(generator).nextCode();
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
        verify(repository).recordExistingUrlAccess(null, NOW);
    }

    @Test
    void failsWithConflictAfterBoundedCollisionRetries() {
        when(generator.nextCode()).thenReturn("taken1");
        when(repository.existsByShortCode("taken1")).thenReturn(true);

        assertThatThrownBy(() -> service.createShortUrl("https://example.com"))
                .isInstanceOf(ShortCodeGenerationException.class);
    }
}
