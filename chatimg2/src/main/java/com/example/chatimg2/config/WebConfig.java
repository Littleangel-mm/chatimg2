package com.example.chatimg2.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final ImageStorageConfig storageConfig;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = storageConfig.getBackupDir().toUri().toString();
        if (!location.endsWith("/")) {
            location += "/";
        }
        registry.addResourceHandler("/img/**")
                .addResourceLocations(location);
    }
}
