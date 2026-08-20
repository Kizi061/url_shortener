package com.example.urlshortener.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class ShortCodeGenerator {

    static final int CODE_LENGTH = 6;
    private static final char[] ALPHANUMERIC =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    private final SecureRandom secureRandom;

    public ShortCodeGenerator() {
        this(new SecureRandom());
    }

    ShortCodeGenerator(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    public String nextCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int index = 0; index < CODE_LENGTH; index++) {
            code.append(ALPHANUMERIC[secureRandom.nextInt(ALPHANUMERIC.length)]);
        }
        return code.toString();
    }
}
