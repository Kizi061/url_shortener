package com.example.urlshortener.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateShortUrlRequest(
        @NotBlank(message = "Original URL is required.") String originalUrl) {
}
