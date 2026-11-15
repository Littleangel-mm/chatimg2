package com.example.chatseedance2.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.video")
public record VideoProperties(
        long debounceWindowMs,
        long pollIntervalMs,
        long pollDelayedIntervalMs,
        int pollBatchSize,
        long taskLockTtlMs,
        Executor executor,
        String storagePath,
        String publicBaseUrl,
        long maxImageBytes,
        long maxVideoBytes,
        long maxAudioBytes
) {
    public record Executor(int coreSize, int maxSize, int queueCapacity) {
    }
}
