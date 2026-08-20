package com.example.urlshortener.service;

import com.example.urlshortener.exception.InvalidUrlException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

@Component
public class UrlValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    public void validate(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidUrlException("Original URL is required.");
        }

        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null
                    || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))
                    || uri.getHost() == null
                    || uri.getHost().isBlank()) {
                throw invalidUrl();
            }
        } catch (URISyntaxException exception) {
            throw invalidUrl();
        }
    }

    private InvalidUrlException invalidUrl() {
        return new InvalidUrlException("URL must be a valid absolute HTTP or HTTPS URL.");
    }
}
