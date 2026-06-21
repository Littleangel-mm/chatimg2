package com.example.chatimg2.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Component
@Getter
public class ImageStorageConfig {

    @Value("${app.image.backup-path:./img/}")
    private String backupPath;

    private Path backupDir;
    private Path sourcesDir;

    @PostConstruct
    void init() throws IOException {
        backupDir = Paths.get(backupPath).toAbsolutePath().normalize();
        sourcesDir = backupDir.resolve("sources");
        Files.createDirectories(backupDir);
        Files.createDirectories(sourcesDir);
        log.info("Image storage directory: {}", backupDir);
    }

    public String toLocalPath(Path file) {
        return file.toAbsolutePath().normalize().toString();
    }

    public String toPublicUrl(Path file) {
        return "/img/" + file.getFileName();
    }
}
