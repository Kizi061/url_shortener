package com.example.urlshortener.dto;

import java.time.Instant;

/** Read-only projection of the existing aggregate activity fields. */
public record ShortUrlAnalyticsResponse(
        String shortCode,
        long accessReuseCount,
        Instant lastRecordedActivityAt,
        boolean hasRecordedActivity) {
}
