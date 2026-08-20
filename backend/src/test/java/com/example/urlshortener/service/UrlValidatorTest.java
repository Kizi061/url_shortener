package com.example.urlshortener.service;

import com.example.urlshortener.exception.InvalidUrlException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlValidatorTest {

    private final UrlValidator validator = new UrlValidator();

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "   ",
            "example.com/path",
            "/relative/path",
            "ftp://example.com/file",
            "mailto:user@example.com",
            "https:///missing-host",
            "http://",
            "://broken",
            "https://exa mple.com"
    })
    void rejectsMissingMalformedOrUnsupportedUrls(String value) {
        assertThatThrownBy(() -> validator.validate(value))
                .isInstanceOf(InvalidUrlException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com",
            "http://example.com/path?item=1#details",
            "HTTP://EXAMPLE.COM/path",
            "https://localhost:8443/test"
    })
    void acceptsAbsoluteHttpAndHttpsUrls(String value) {
        assertThatCode(() -> validator.validate(value))
                .doesNotThrowAnyException();
    }
}
