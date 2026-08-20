package com.example.urlshortener.orchestrator.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Minimal JDK-only loader for retry.implementation.maxAttempts. */
public record RetryConfiguration(int implementationMaxAttempts) {
    public RetryConfiguration {
        if (implementationMaxAttempts < 1) {
            throw new IllegalArgumentException("implementation maxAttempts must be at least 1");
        }
    }

    public static RetryConfiguration load(Path path) throws IOException {
        int maxAttempts = -1;
        for (String line : Files.readAllLines(path)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("maxAttempts:")) {
                maxAttempts = Integer.parseInt(trimmed.substring("maxAttempts:".length()).trim());
            }
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("retry.implementation.maxAttempts is missing or invalid");
        }
        return new RetryConfiguration(maxAttempts);
    }
}
