package com.example.urlshortener.dto;

public record ShortUrlResponse(String shortCode, String shortUrl, String originalUrl) {
}
