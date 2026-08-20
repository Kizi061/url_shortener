package com.example.urlshortener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record UrlShortenerProperties(String baseUrl, String allowedOrigin) {

    public UrlShortenerProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8080";
        }
        baseUrl = baseUrl.replaceAll("/+$", "");

        if (allowedOrigin == null || allowedOrigin.isBlank()) {
            allowedOrigin = "http://localhost:5173";
        }
    }
}
