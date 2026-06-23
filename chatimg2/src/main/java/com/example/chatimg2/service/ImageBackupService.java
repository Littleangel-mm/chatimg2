package com.example.chatimg2.service;

import com.example.chatimg2.config.ImageStorageConfig;
import com.example.chatimg2.repository.GenerationRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageBackupService {

    private final GenerationRecordRepository recordRepository;
    private final ImageStorageConfig storageConfig;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    public record BackupResult(String imagePath) {}

    /** 将 base64 图片保存到 img 目录（API 中间步骤，临时文件名） */
    public String saveBase64(String b64) throws IOException {
        Path filePath = storageConfig.getBackupDir().resolve(UUID.randomUUID() + ".png");
        Files.write(filePath, Base64.getDecoder().decode(b64));
        return filePath.getFileName().toString();
    }

    /** 下载/保存图片到 img 目录，返回相对路径（文件名） */
    public BackupResult backupSync(String imageUrl, String taskCode) {
        Path target = targetPath(taskCode);
        saveToImgDir(imageUrl, target);
        String imagePath = taskCode + ".png";
        log.info("Saved generated image to img/{}", imagePath);
        return new BackupResult(imagePath);
    }

    @Async
    public void backupAsync(Integer recordId, String imageUrl, String taskCode) {
        try {
            BackupResult result = backupSync(imageUrl, taskCode);
            recordRepository.findById(recordId).ifPresent(record -> {
                record.setImagePath(result.imagePath());
                recordRepository.save(record);
            });
        } catch (Exception e) {
            log.warn("Async backup failed for record {}: {}", recordId, e.getMessage());
        }
    }

    private void saveToImgDir(String imageUrl, Path target) {
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new RuntimeException("图片地址为空，无法保存");
        }

        if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) {
            downloadToImgDir(imageUrl, target);
            return;
        }

        Path existing = resolveExistingPath(imageUrl);
        if (existing != null && Files.exists(existing)) {
            copyToTarget(existing, target);
            return;
        }

        throw new RuntimeException("无法保存图片到本地: " + imageUrl);
    }

    private Path resolveExistingPath(String imageUrl) {
        if (imageUrl.startsWith("/api/images/") || imageUrl.startsWith("/img/")) {
            String fileName = imageUrl.substring(imageUrl.lastIndexOf('/') + 1);
            return storageConfig.getBackupDir().resolve(fileName);
        }
        if (!imageUrl.contains("/") && !imageUrl.startsWith("http")) {
            return storageConfig.getBackupDir().resolve(imageUrl);
        }
        return null;
    }

    private void copyToTarget(Path source, Path target) {
        try {
            if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
                return;
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            if (isTempIntermediateFile(source)) {
                Files.deleteIfExists(source);
                log.debug("Removed temp image {}", source.getFileName());
            }
        } catch (IOException e) {
            throw new RuntimeException("复制图片失败: " + e.getMessage(), e);
        }
    }

    private boolean isTempIntermediateFile(Path source) {
        if (source == null || source.getFileName() == null) {
            return false;
        }
        String name = source.getFileName().toString();
        return name.matches("[0-9a-fA-F\\-]{36}\\.png")
                && source.getParent() != null
                && source.getParent().equals(storageConfig.getBackupDir());
    }

    private Path targetPath(String taskCode) {
        return storageConfig.getBackupDir().resolve(taskCode + ".png");
    }

    private void downloadToImgDir(String imageUrl, Path target) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Request request = new Request.Builder().url(imageUrl).build();
                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        try (InputStream is = response.body().byteStream()) {
                            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                        return;
                    }
                    lastError = new RuntimeException("HTTP " + response.code());
                }
            } catch (Exception e) {
                lastError = new RuntimeException(e.getMessage(), e);
                log.warn("Download attempt {}/3 failed: {}", attempt, e.getMessage());
            }
        }
        throw new RuntimeException("图片下载保存失败: " + (lastError != null ? lastError.getMessage() : "unknown"));
    }
}
