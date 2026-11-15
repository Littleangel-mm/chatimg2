package com.example.chatseedance2.config;

import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class HttpClientConfig {

    @Bean
    public OkHttpClient seedanceHttpClient(SeedanceApiProperties properties) {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(Math.max(1000L, properties.connectTimeoutMs())))
                .readTimeout(Duration.ofMillis(Math.max(1000L, properties.readTimeoutMs())))
                .callTimeout(Duration.ofMillis(Math.max(1000L, properties.readTimeoutMs())))
                .retryOnConnectionFailure(false)
                .build();
    }
}
