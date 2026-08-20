package com.example.urlshortener.exception;

public class ShortCodeGenerationException extends RuntimeException {
    public ShortCodeGenerationException() {
        super("A unique short code could not be generated. Please try again.");
    }
}
