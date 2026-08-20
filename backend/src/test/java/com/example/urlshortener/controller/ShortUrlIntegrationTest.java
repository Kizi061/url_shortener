package com.example.urlshortener.controller;

import com.example.urlshortener.domain.ShortUrl;
import com.example.urlshortener.repository.ShortUrlRepository;
import com.example.urlshortener.service.OriginalUrlHasher;
import com.example.urlshortener.service.ShortCodeGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShortUrlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ShortUrlRepository repository;
    @MockitoBean
    private ShortCodeGenerator generator;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void postCreatesAndPersistsShortUrl() throws Exception {
        when(generator.nextCode()).thenReturn("aB12Cd");

        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalUrl":"https://www.example.com/products/category/item/12345"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value("aB12Cd"))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/aB12Cd"))
                .andExpect(jsonPath("$.originalUrl")
                        .value("https://www.example.com/products/category/item/12345"));

        ShortUrl saved = repository.findByShortCode("aB12Cd").orElseThrow();
        assertThat(saved.getCreatedTimestamp()).isNotNull();
        assertThat(saved.getLastAccessedTimestamp()).isEqualTo(saved.getCreatedTimestamp());
        assertThat(saved.getExpiresAt())
                .isEqualTo(saved.getCreatedTimestamp().atZone(java.time.ZoneOffset.UTC)
                        .plusMonths(1)
                        .toInstant());
        assertThat(saved.getClickCount()).isZero();
    }

    @Test
    void repeatedPostReturnsExistingMappingWithoutDuplicateRecord() throws Exception {
        when(generator.nextCode()).thenReturn("same01");
        String request = """
                {"originalUrl":"https://www.example.com/repeated"}
                """;

        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value("same01"));

        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value("same01"))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/same01"));

        assertThat(repository.count()).isEqualTo(1);
        ShortUrl reused = repository.findByShortCode("same01").orElseThrow();
        assertThat(reused.getClickCount()).isEqualTo(1);
        assertThat(reused.getLastAccessedTimestamp())
                .isAfterOrEqualTo(reused.getCreatedTimestamp());
        verify(generator, times(1)).nextCode();
    }

    @Test
    void postRejectsInvalidUrl() throws Exception {
        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originalUrl\":\"not-a-url\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_URL"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void getRedirectsToOriginalUrl() throws Exception {
        repository.save(new ShortUrl(
                "aB12Cd",
                "http://localhost:8080/aB12Cd",
                "https://www.example.com/products/category/item/12345",
                new OriginalUrlHasher().hash(
                        "https://www.example.com/products/category/item/12345"),
                Instant.now()));

        mockMvc.perform(get("/aB12Cd"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://www.example.com/products/category/item/12345"));

        ShortUrl accessed = repository.findByShortCode("aB12Cd").orElseThrow();
        assertThat(accessed.getClickCount()).isEqualTo(1);
        assertThat(accessed.getLastAccessedTimestamp()).isNotNull();
    }

    @Test
    void inactiveShortCodeReturns404() throws Exception {
        ShortUrl inactive = new ShortUrl(
                "off123",
                "http://localhost:8080/off123",
                "https://example.com/inactive",
                new OriginalUrlHasher().hash("https://example.com/inactive"),
                Instant.now());
        inactive.deactivate();
        repository.save(inactive);

        mockMvc.perform(get("/off123"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHORT_URL_NOT_FOUND"));
    }

    @Test
    void expiredShortCodeReturns404() throws Exception {
        ShortUrl expired = new ShortUrl(
                "old123",
                "http://localhost:8080/old123",
                "https://example.com/expired",
                new OriginalUrlHasher().hash("https://example.com/expired"),
                Instant.now().minusSeconds(120));
        expired.setExpiresAt(Instant.now().minusSeconds(60));
        repository.save(expired);

        mockMvc.perform(get("/old123"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHORT_URL_NOT_FOUND"));
    }

    @Test
    void getUnknownShortCodeReturns404() throws Exception {
        mockMvc.perform(get("/xxxxxx"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHORT_URL_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Short URL was not found."));
    }
}
