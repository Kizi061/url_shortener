package com.example.urlshortener.repository;

import com.example.urlshortener.domain.ShortUrl;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {
    boolean existsByShortCode(String shortCode);
    Optional<ShortUrl> findByShortCode(String shortCode);
}
