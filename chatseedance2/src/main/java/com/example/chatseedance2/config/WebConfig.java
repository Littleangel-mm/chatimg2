package com.example.chatseedance2.config;

import com.example.chatseedance2.security.RbacInterceptor;
import com.example.chatseedance2.service.VideoStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration("videoWebConfig")
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final RbacInterceptor rbacInterceptor;
    private final VideoStorageService storageService;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rbacInterceptor).addPathPatterns("/api/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/video-media/**")
                .addResourceLocations(storageService.resourceLocation());
    }
}
