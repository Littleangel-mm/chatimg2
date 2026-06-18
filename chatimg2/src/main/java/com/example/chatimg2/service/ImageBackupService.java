package com.example.chatimg2.service;

import com.example.chatimg2.repository.GenerationRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageBackupService {

    private final GenerationRecordRepository recordRepository;

    @Value("${app.image.backup-path}")
    private String backupPath;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    @Async
    public void backupAsync(Integer recordId, String imageUrl) {
        try {
            String localPath = downloadAndBackup(imageUrl);
            if (localPath == null) return;
            recordRepository.findById(recordId).ifPresent(record -> {
                record.setLocalPath(localPath);
                recordRepository.save(record);
            });
        } catch (Exception e) {
            log.warn("Async backup failed for record {}: {}", recordId, e.getMessage());
        }
    }

    private String downloadAndBackup(String imageUrl) {
        if (imageUrl.startsWith("/api/images/")) {
            return imageUrl.replace("/api/images/", "./img/");
        }
        try {
            Path dir = Paths.get(backupPath);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }

            String fileName = UUID.randomUUID() + ".png";
            Path filePath = dir.resolve(fileName);

            Request request = new Request.Builder().url(imageUrl).build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    try (InputStream is = response.body().byteStream()) {
                        Files.copy(is, filePath);
                    }
                    return filePath.toString();
                }
            }
        } catch (Exception e) {
            log.warn("Image backup failed: {}", e.getMessage());
        }
        return null;
    }
}
