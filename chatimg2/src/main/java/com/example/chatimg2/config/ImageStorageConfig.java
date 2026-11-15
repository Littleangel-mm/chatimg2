package com.example.chatimg2.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
@Component
@Getter
public class ImageStorageConfig {

    @Value("${app.image.backup-path:}")
    private String backupPath;

    @Value("${app.inspiration.media-path:}")
    private String inspirationMediaPath;

    private Path backupDir;
    private Path sourcesDir;
    private Path inspirationMediaDir;
    private Path upimgDir;
    private Path upvideoDir;
    private List<Path> readableImageDirs = List.of();

    @PostConstruct
    void init() throws IOException {
        backupDir = resolveBackupDir();
        sourcesDir = backupDir.resolve("sources");
        inspirationMediaDir = resolveInspirationMediaDir();
        upimgDir = inspirationMediaDir.resolve("upimg");
        upvideoDir = inspirationMediaDir.resolve("upvideo");
        Files.createDirectories(backupDir);
        Files.createDirectories(sourcesDir);
        Files.createDirectories(inspirationMediaDir);
        Files.createDirectories(upimgDir);
        Files.createDirectories(upvideoDir);
        readableImageDirs = resolveReadableImageDirs();
        log.info("Generated image storage directory: {}", backupDir);
        log.info("Inspiration media directory: {}", inspirationMediaDir);
        if (readableImageDirs.size() > 1) {
            log.info("Image readable fallback directories: {}", readableImageDirs.subList(1, readableImageDirs.size()));
        }
    }

    private Path resolveBackupDir() {
        if (backupPath != null && !backupPath.isBlank()) {
            Path configured = Paths.get(backupPath);
            if (configured.isAbsolute()) {
                return configured.normalize();
            }
            return applicationRoot().resolve(configured).normalize();
        }
        return applicationRoot().resolve("img").normalize();
    }

    private Path resolveInspirationMediaDir() {
        if (inspirationMediaPath != null && !inspirationMediaPath.isBlank()) {
            Path configured = Paths.get(inspirationMediaPath);
            if (configured.isAbsolute()) {
                return configured.normalize();
            }
            return developmentProjectRoot().resolve(configured).normalize();
        }
        return developmentProjectRoot().resolve("img").normalize();
    }

    private Path applicationRoot() {
        Path home = new ApplicationHome(ImageStorageConfig.class).getDir().toPath().toAbsolutePath().normalize();
        if (home.endsWith(Paths.get("target", "classes"))) {
            return home.getParent().getParent().normalize();
        }
        if (home.endsWith("target")) {
            return home.getParent().normalize();
        }
        return home;
    }

    private Path developmentProjectRoot() {
        Path appRoot = applicationRoot();
        Path parent = appRoot.getParent();
        if (isProjectRoot(parent)) {
            return parent.normalize();
        }
        if (isProjectRoot(appRoot)) {
            return appRoot.normalize();
        }
        return appRoot;
    }

    private boolean isProjectRoot(Path path) {
        return path != null
                && Files.isDirectory(path.resolve(".git"))
                && Files.isDirectory(path.resolve("frontend"));
    }

    private List<Path> resolveReadableImageDirs() {
        List<Path> dirs = new ArrayList<>();
        addIfMissing(dirs, backupDir);
        addIfMissing(dirs, inspirationMediaDir);

        Path legacyModuleImg = developmentProjectRoot().resolve("chatimg2").resolve("img").normalize();
        if (Files.isDirectory(legacyModuleImg) && !legacyModuleImg.equals(backupDir)) {
            addIfMissing(dirs, legacyModuleImg);
        }
        return List.copyOf(dirs);
    }

    private static void addIfMissing(List<Path> dirs, Path dir) {
        Path normalized = dir.toAbsolutePath().normalize();
        if (dirs.stream().map(path -> path.toAbsolutePath().normalize()).noneMatch(normalized::equals)) {
            dirs.add(dir);
        }
    }

    public List<String> imageResourceLocations() {
        return readableImageDirs.stream()
                .map(dir -> {
                    String location = dir.toUri().toString();
                    return location.endsWith("/") ? location : location + "/";
                })
                .toList();
    }

    public String upimgResourceLocation() {
        return resourceLocation(upimgDir);
    }

    public String upvideoResourceLocation() {
        return resourceLocation(upvideoDir);
    }

    private String resourceLocation(Path dir) {
        String location = dir.toUri().toString();
        return location.endsWith("/") ? location : location + "/";
    }

    public Path resolveReadableImageFile(String relativePath) {
        String normalized = normalizeRelativeImagePath(relativePath);
        if (normalized.startsWith("upimg/")) {
            Path file = upimgDir.resolve(normalized.substring("upimg/".length())).normalize();
            if (file.startsWith(upimgDir) && Files.isRegularFile(file)) {
                return file;
            }
        }
        if (normalized.startsWith("upvideo/")) {
            Path file = upvideoDir.resolve(normalized.substring("upvideo/".length())).normalize();
            if (file.startsWith(upvideoDir) && Files.isRegularFile(file)) {
                return file;
            }
        }
        for (Path dir : readableImageDirs) {
            Path file = dir.resolve(normalized).normalize();
            if (file.startsWith(dir) && Files.isRegularFile(file)) {
                return file;
            }
        }
        if (!normalized.contains("/")) {
            for (Path dir : readableImageDirs) {
                Path file = findReadableFileByName(dir, normalized);
                if (file != null) {
                    return file;
                }
            }
        }
        return null;
    }

    private Path findReadableFileByName(Path dir, String fileName) {
        Path root = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> stream = Files.find(root, 4,
                (path, attrs) -> attrs.isRegularFile()
                        && path.getFileName() != null
                        && fileName.equals(path.getFileName().toString()))) {
            return stream.findFirst().orElse(null);
        } catch (IOException e) {
            log.debug("Image fallback lookup failed in {}: {}", root, e.getMessage());
            return null;
        }
    }

    public static String normalizeRelativeImagePath(String path) {
        String normalized = path == null ? "" : URLDecoder.decode(path, StandardCharsets.UTF_8).replace('\\', '/');
        if (normalized.startsWith("/api/images/")) {
            normalized = normalized.substring("/api/images/".length());
        } else if (normalized.startsWith("api/images/")) {
            normalized = normalized.substring("api/images/".length());
        } else if (normalized.startsWith("/img/")) {
            normalized = normalized.substring("/img/".length());
        } else if (normalized.startsWith("img/")) {
            normalized = normalized.substring("img/".length());
        } else if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    public String toLocalPath(Path file) {
        return file.toAbsolutePath().normalize().toString();
    }

    public String toPublicUrl(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        for (Path dir : readableImageDirs) {
            Path root = dir.toAbsolutePath().normalize();
            if (normalized.startsWith(root)) {
                return "/img/" + root.relativize(normalized).toString().replace('\\', '/');
            }
        }
        return "/img/" + file.getFileName();
    }
}
