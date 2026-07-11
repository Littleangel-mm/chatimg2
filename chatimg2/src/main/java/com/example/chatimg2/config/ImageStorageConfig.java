package com.example.chatimg2.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Component
@Getter
public class ImageStorageConfig {

    @Value("${app.image.backup-path:}")
    private String backupPath;

    private Path backupDir;
    private Path sourcesDir;
    private Path upimgDir;
    private Path upvideoDir;

    @PostConstruct
    void init() throws IOException {
        backupDir = resolveBackupDir();
        sourcesDir = backupDir.resolve("sources");
        upimgDir = backupDir.resolve("upimg");
        upvideoDir = backupDir.resolve("upvideo");
        Files.createDirectories(backupDir);
        Files.createDirectories(sourcesDir);
        Files.createDirectories(upimgDir);
        Files.createDirectories(upvideoDir);
        log.info("Image storage directory: {}", backupDir);
    }

    private Path resolveBackupDir() {
        if (backupPath != null && !backupPath.isBlank()) {
            Path configured = Paths.get(backupPath);
            if (configured.isAbsolute()) {
                return configured.normalize();
            }
            return applicationDir().resolve(configured).normalize();
        }
        return applicationDir().resolve("img").normalize();
    }

    private Path applicationDir() {
        Path home = new ApplicationHome(ImageStorageConfig.class).getDir().toPath().toAbsolutePath().normalize();
        if (home.endsWith(Paths.get("target", "classes"))) {
            return resolveDevelopmentRoot(home.getParent().getParent().normalize());
        }
        if (home.endsWith("target")) {
            return resolveDevelopmentRoot(home.getParent().normalize());
        }
        return home;
    }

    private Path resolveDevelopmentRoot(Path moduleDir) {
        Path parent = moduleDir.getParent();
        if (parent != null
                && Files.isDirectory(parent.resolve(".git"))
                && Files.isDirectory(parent.resolve("frontend"))) {
            return parent.normalize();
        }
        return moduleDir;
    }

    public String toLocalPath(Path file) {
        return file.toAbsolutePath().normalize().toString();
    }

    public String toPublicUrl(Path file) {
        return "/img/" + file.getFileName();
    }
}
