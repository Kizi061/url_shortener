package com.example.urlshortener.controller;

import com.example.urlshortener.dto.ShortUrlResponse;
import com.example.urlshortener.exception.InvalidUrlException;
import com.example.urlshortener.exception.ShortCodeGenerationException;
import com.example.urlshortener.exception.ShortUrlNotFoundException;
import com.example.urlshortener.service.ShortUrlCreationResult;
import com.example.urlshortener.service.ShortUrlService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ShortUrlController.class)
class ShortUrlControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShortUrlService service;

    @Test
    void returns201WhenMappingIsCreated() throws Exception {
        ShortUrlResponse response = new ShortUrlResponse(
                "aB12Cd", "http://localhost:8080/aB12Cd", "https://example.com");
        when(service.createShortUrl("https://example.com"))
                .thenReturn(new ShortUrlCreationResult(response, true));

        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalUrl":"https://example.com"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value("aB12Cd"))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/aB12Cd"))
                .andExpect(jsonPath("$.originalUrl").value("https://example.com"));
    }

    @Test
    void returns200WhenExistingMappingIsReused() throws Exception {
        ShortUrlResponse response = new ShortUrlResponse(
                "old123", "http://localhost:8080/old123", "https://example.com/existing");
        when(service.createShortUrl("https://example.com/existing"))
                .thenReturn(new ShortUrlCreationResult(response, false));

        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalUrl":"https://example.com/existing"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value("old123"));
    }

    @Test
    void returns302WithLocationForKnownCode() throws Exception {
        when(service.getOriginalUrl("aB12Cd")).thenReturn("https://example.com/destination");

        mockMvc.perform(get("/aB12Cd"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/destination"))
                .andExpect(content().string(""));
    }

    @Test
    void rejectsBlankUrlBeforeCallingService() throws Exception {
        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalUrl":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Original URL is required."))
                .andExpect(jsonPath("$.timestamp").exists());

        verifyNoInteractions(service);
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originalUrl\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("Request body is missing or malformed."));

        verifyNoInteractions(service);
    }

    @Test
    void mapsInvalidUrlTo400() throws Exception {
        when(service.createShortUrl("not-a-url"))
                .thenThrow(new InvalidUrlException(
                        "URL must be a valid absolute HTTP or HTTPS URL."));

        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalUrl":"not-a-url"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_URL"));
    }

    @Test
    void mapsCollisionExhaustionTo409() throws Exception {
        when(service.createShortUrl("https://example.com"))
                .thenThrow(new ShortCodeGenerationException());

        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalUrl":"https://example.com"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHORT_CODE_CONFLICT"));
    }

    @Test
    void mapsUnavailableCodeTo404() throws Exception {
        when(service.getOriginalUrl("missing"))
                .thenThrow(new ShortUrlNotFoundException("missing"));

        mockMvc.perform(get("/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHORT_URL_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Short URL was not found."));
    }

    @Test
    void sanitizesDatabaseFailureAs500() throws Exception {
        when(service.getOriginalUrl("dbfail"))
                .thenThrow(new DataAccessResourceFailureException(
                        "database offline at jdbc:mysql://internal-host"));

        mockMvc.perform(get("/dbfail"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."))
                .andExpect(content().string(not(containsString("internal-host"))));
    }
}
