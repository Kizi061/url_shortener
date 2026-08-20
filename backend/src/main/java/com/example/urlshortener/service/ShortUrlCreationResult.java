package com.example.urlshortener.service;

import com.example.urlshortener.dto.ShortUrlResponse;

public record ShortUrlCreationResult(ShortUrlResponse response, boolean created) {
}
