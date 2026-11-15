package com.example.chatimg2.service;

import com.example.chatimg2.entity.GenerationRecord;
import com.example.chatimg2.repository.GenerationRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageResultDeliveryService {

    private final ImageBackupService imageBackupService;
    private final GenerationRecordService recordService;
    private final GenerationRecordRepository recordRepository;
    private final DistributedLockService distributedLockService;
    private final AuthService authService;

    @Value("${app.image.backup.lock-ttl-ms:600000}")
    private long backupLockTtlMs;

    public boolean saveLocal(Integer recordId, String remoteImageUrl, String taskCode, String ownerFolder) {
        String lockKey = "image-backup:record:" + recordId;
        if (!distributedLockService.acquire(lockKey, Duration.ofMillis(backupLockTtlMs))) {
            log.debug("Image backup already running: recordId={}", recordId);
            return false;
        }
        try {
            GenerationRecord current = recordRepository.findById(recordId).orElse(null);
            if (current == null) {
                return false;
            }
            if (current.getImagePath() != null && !current.getImagePath().isBlank()) {
                return true;
            }
            String resultUrl = remoteImageUrl == null || remoteImageUrl.isBlank()
                    ? current.getRemoteImageUrl()
                    : remoteImageUrl;
            if (resultUrl == null || resultUrl.isBlank()) {
                return false;
            }

            ImageBackupService.BackupResult backup = imageBackupService.backupSync(
                    resultUrl,
                    taskCode == null || taskCode.isBlank() ? current.getTaskCode() : taskCode,
                    ownerFolder);
            recordService.markCompleted(recordId, backup.imagePath());
            return true;
        } finally {
            distributedLockService.release(lockKey);
        }
    }

    @Async("imageBackupExecutor")
    public void retrySavedRemoteImage(Integer recordId) {
        GenerationRecord record = recordRepository.findById(recordId).orElse(null);
        if (record == null || !record.isRemoteImageAvailable()) {
            return;
        }
        String ownerFolder = record.getUserEmail() == null || record.getUserEmail().isBlank()
                ? null
                : authService.safeEmailFolder(record.getUserEmail());
        try {
            boolean saved = saveLocal(recordId, record.getRemoteImageUrl(), record.getTaskCode(), ownerFolder);
            if (saved) {
                log.info("Remote image backup completed: recordId={}, taskCode={}", recordId, record.getTaskCode());
            }
        } catch (Exception e) {
            log.warn("Remote image backup still unavailable: recordId={}, taskCode={}, reason={}",
                    recordId, record.getTaskCode(), e.getMessage());
        }
    }

    public void saveBrowserDeliveredImage(Integer recordId, byte[] imageBytes) {
        GenerationRecord record = recordRepository.findById(recordId)
                .orElseThrow(() -> new IllegalArgumentException("Generation record not found"));
        if (record.getImagePath() != null && !record.getImagePath().isBlank()) {
            return;
        }
        if (!record.isRemoteImageAvailable()) {
            throw new IllegalStateException("Remote image result is not available");
        }
        String ownerFolder = record.getUserEmail() == null || record.getUserEmail().isBlank()
                ? null
                : authService.safeEmailFolder(record.getUserEmail());
        ImageBackupService.BackupResult backup = imageBackupService.backupBytes(
                imageBytes, record.getTaskCode(), ownerFolder);
        recordService.markCompleted(recordId, backup.imagePath());
    }
}
