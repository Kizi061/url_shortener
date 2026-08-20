package com.example.urlshortener.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final UrlShortenerProperties properties;

    public WebConfig(UrlShortenerProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(properties.allowedOrigin())
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Content-Type");
    }
}
