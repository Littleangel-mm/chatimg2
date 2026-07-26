package com.example.chatimg2.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    @Value("${app.generation.executor.core-size:2}")
    private int generationCoreSize;

    @Value("${app.generation.executor.max-size:8}")
    private int generationMaxSize;

    @Value("${app.generation.executor.queue-capacity:100}")
    private int generationQueueCapacity;

    @Value("${app.image.backup.executor.core-size:1}")
    private int backupCoreSize;

    @Value("${app.image.backup.executor.max-size:2}")
    private int backupMaxSize;

    @Value("${app.image.backup.executor.queue-capacity:50}")
    private int backupQueueCapacity;

    @Bean(name = "generationExecutor")
    public Executor generationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int coreSize = Math.max(1, generationCoreSize);
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(Math.max(coreSize, generationMaxSize));
        executor.setQueueCapacity(Math.max(0, generationQueueCapacity));
        executor.setThreadNamePrefix("gen-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean(name = "imageBackupExecutor")
    public Executor imageBackupExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int coreSize = Math.max(1, backupCoreSize);
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(Math.max(coreSize, backupMaxSize));
        executor.setQueueCapacity(Math.max(0, backupQueueCapacity));
        executor.setThreadNamePrefix("image-backup-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();
        return executor;
    }
}
