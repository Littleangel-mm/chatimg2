package com.example.chatseedance2.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.seedance.api")
public record SeedanceApiProperties(
        String url,
        String key,
        String model,
        long connectTimeoutMs,
        long readTimeoutMs,
        long modelCacheMs
) {
}
