package com.example.chatseedance2.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration("videoAsyncConfig")
@EnableConfigurationProperties({VideoProperties.class, SeedanceApiProperties.class})
@RequiredArgsConstructor
public class AsyncConfig {

    private final VideoProperties properties;

    @Bean(name = "videoTaskExecutor")
    public Executor videoTaskExecutor() {
        VideoProperties.Executor settings = properties.executor();
        int coreSize = Math.max(1, settings.coreSize());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(Math.max(coreSize, settings.maxSize()));
        executor.setQueueCapacity(Math.max(0, settings.queueCapacity()));
        executor.setThreadNamePrefix("video-task-");
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
