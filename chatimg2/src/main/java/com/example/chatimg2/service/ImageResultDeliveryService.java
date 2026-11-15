package com.example.chatimg2.service;

import com.example.chatimg2.config.ImageStorageConfig;
import com.example.chatimg2.entity.GenerationRecord;
import com.example.chatimg2.repository.GenerationRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageResultDeliveryService {

    private final ImageBackupService imageBackupService;
    private final GenerationRecordService recordService;
    private final GenerationRecordRepository recordRepository;
    private final DistributedLockService distributedLockService;
    private final ImageStorageConfig storageConfig;
    private final AuthService authService;

    @Value("${app.image.backup.lock-ttl-ms:600000}")
    private long backupLockTtlMs;

    @Async("imageBackupExecutor")
    public void retrySavedRemoteImage(Integer recordId) {
        String lockKey = "image-backup:record:" + recordId;
        Optional<DistributedLockService.LockToken> lock;
        String taskCode = "unknown";
        try {
            lock = distributedLockService.tryAcquire(lockKey, Duration.ofMillis(backupLockTtlMs));
        } catch (Exception e) {
            log.warn("Remote image backup lock failed: recordId={}, reason={}", recordId, e.getMessage());
            return;
        }
        if (lock.isEmpty()) {
            return;
        }
        try {
            GenerationRecord record = recordRepository.findById(recordId).orElse(null);
            if (!requiresLocalBackup(record)) {
                return;
            }
            taskCode = record.getTaskCode();
            String ownerFolder = ownerFolder(record);
            ImageBackupService.BackupResult backup = imageBackupService.backupSync(
                    record.getRemoteImageUrl(), record.getTaskCode(), ownerFolder);
            if (recordService.markLocalImageAvailable(recordId, backup.imagePath())) {
                log.info("Remote image backup completed: recordId={}, taskCode={}",
                        recordId, record.getTaskCode());
            }
        } catch (Exception e) {
            log.warn("Remote image backup unavailable: recordId={}, taskCode={}, reason={}",
                    recordId, taskCode, e.getMessage());
        } finally {
            distributedLockService.release(lock.orElse(null));
        }
    }

    public void saveBrowserDeliveredImage(Integer recordId, byte[] imageBytes) {
        GenerationRecord record = recordRepository.findById(recordId)
                .orElseThrow(() -> new IllegalArgumentException("Generation record not found"));
        if (hasReadableLocalImage(record)) {
            return;
        }
        if (!GenerationState.isCompleted(record.getStatus()) || !record.isRemoteImageAvailable()) {
            throw new IllegalStateException("Remote image result is not available");
        }
        ImageBackupService.BackupResult backup = imageBackupService.backupBytes(
                imageBytes, record.getTaskCode(), ownerFolder(record));
        recordService.markLocalImageAvailable(recordId, backup.imagePath());
    }

    private boolean requiresLocalBackup(GenerationRecord record) {
        return record != null
                && GenerationState.isCompleted(record.getStatus())
                && record.isRemoteImageAvailable()
                && !hasReadableLocalImage(record);
    }

    private boolean hasReadableLocalImage(GenerationRecord record) {
        return record != null
                && record.getImagePath() != null
                && !record.getImagePath().isBlank()
                && storageConfig.resolveReadableImageFile(record.getImagePath()) != null;
    }

    private String ownerFolder(GenerationRecord record) {
        return record.getUserEmail() == null || record.getUserEmail().isBlank()
                ? null
                : authService.safeEmailFolder(record.getUserEmail());
    }
}
