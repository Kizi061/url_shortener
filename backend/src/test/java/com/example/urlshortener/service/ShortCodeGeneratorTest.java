package com.example.urlshortener.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ShortCodeGeneratorTest {

    private final ShortCodeGenerator generator = new ShortCodeGenerator();

    @Test
    void generatesSixCharacterAlphanumericCodes() {
        assertThat(generator.nextCode()).matches("[a-zA-Z0-9]{6}");
    }

    @Test
    void producesVariedCodes() {
        Set<String> generatedCodes = new HashSet<>();
        for (int count = 0; count < 100; count++) {
            generatedCodes.add(generator.nextCode());
        }
        assertThat(generatedCodes).hasSizeGreaterThan(95);
    }
}
