package com.example.urlshortener.controller;

import com.example.urlshortener.dto.CreateShortUrlRequest;
import com.example.urlshortener.dto.ShortUrlAnalyticsResponse;
import com.example.urlshortener.dto.ShortUrlResponse;
import com.example.urlshortener.service.ShortUrlCreationResult;
import com.example.urlshortener.service.ShortUrlService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping
public class ShortUrlController {

    private final ShortUrlService shortUrlService;

    public ShortUrlController(ShortUrlService shortUrlService) {
        this.shortUrlService = shortUrlService;
    }

    @PostMapping("/api/urls")
    public ResponseEntity<ShortUrlResponse> createShortUrl(
            @Valid @RequestBody CreateShortUrlRequest request) {
        ShortUrlCreationResult result = shortUrlService.createShortUrl(request.originalUrl());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }

    @GetMapping("/api/urls/{shortCode}/analytics")
    public ResponseEntity<ShortUrlAnalyticsResponse> getAnalytics(
            @PathVariable String shortCode) {
        return ResponseEntity.ok(shortUrlService.getAnalytics(shortCode));
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        String originalUrl = shortUrlService.getOriginalUrl(shortCode);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(originalUrl))
                .build();
    }
}
