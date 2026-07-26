package com.example.chatimg2.service;

import com.example.chatimg2.config.ImageStorageConfig;
import com.example.chatimg2.entity.GenerationRecord;
import com.example.chatimg2.repository.GenerationRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GenerationRecoveryService {

    private final DistributedLockService distributedLockService;
    private final GenerationRecordService recordService;
    private final GenerationRecordRepository recordRepository;
    private final ImageStorageConfig storageConfig;
    private final AuthService authService;

    @Value("${app.generation.recovery.enabled:true}")
    private boolean enabled;

    @Value("${app.generation.recovery.stale-after-ms:900000}")
    private long staleAfterMs;

    @Value("${app.generation.recovery.lock-ttl-ms:60000}")
    private long lockTtlMs;

    private static final String RECOVERY_LOCK = "generation:recovery";
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        reconcileLateLocalImages("startup");
        recoverStaleProcessingTasks("startup");
    }

    @Scheduled(
            fixedDelayString = "${app.generation.recovery.interval-ms:60000}",
            initialDelayString = "${app.generation.recovery.initial-delay-ms:60000}"
    )
    public void recoverOnSchedule() {
        reconcileLateLocalImages("scheduled");
        recoverStaleProcessingTasks("scheduled");
    }

    private void recoverStaleProcessingTasks(String trigger) {
        if (!enabled || staleAfterMs <= 0) {
            return;
        }
        Optional<DistributedLockService.LockToken> lock;
        try {
            lock = distributedLockService.tryAcquire(RECOVERY_LOCK, Duration.ofMillis(lockTtlMs));
        } catch (Exception e) {
            log.warn("Skip stale generation recovery because recovery lock failed", e);
            return;
        }
        if (lock.isEmpty()) {
            return;
        }
        try {
            LocalDateTime cutoff = LocalDateTime.now().minus(Duration.ofMillis(staleAfterMs));
            int recovered = recoverRecords(cutoff);
            if (recovered > 0) {
                log.warn("Recovered stale generation tasks: count={}, trigger={}, cutoff={}", recovered, trigger, cutoff);
            }
        } finally {
            distributedLockService.release(lock.orElse(null));
        }
    }

    private int recoverRecords(LocalDateTime cutoff) {
        int recovered = 0;
        for (GenerationRecord record : recordRepository
                .findTop50ByStatusAndImagePathIsNullAndCreatedAtBeforeOrderByCreatedAtAsc(
                        GenerationState.PROCESSING, cutoff)) {
            String recordLock = "generation:record:" + record.getId();
            Optional<DistributedLockService.LockToken> lock;
            try {
                lock = distributedLockService.tryAcquire(recordLock, Duration.ofMillis(lockTtlMs));
            } catch (Exception e) {
                log.warn("Skip stale generation record because record lock failed: recordId={}", record.getId(), e);
                continue;
            }
            if (lock.isEmpty()) {
                continue;
            }
            try {
                if (record.getUserId() != null) {
                    recordService.markFailedForUser(
                            record.getId(), record.getUserId(), GenerationState.INTERRUPTED_MESSAGE);
                } else {
                    recordService.markFailed(record.getId(), record.getKeyId(), GenerationState.INTERRUPTED_MESSAGE);
                }
                recovered++;
            } catch (Exception e) {
                log.error("Failed to recover stale generation: recordId={}", record.getId(), e);
            } finally {
                distributedLockService.release(lock.orElse(null));
            }
        }
        return recovered;
    }

    private void reconcileLateLocalImages(String trigger) {
        int restored = 0;
        for (GenerationRecord record : recordRepository
                .findTop50ByStatusAndImagePathIsNullAndErrorMessageContainingOrderByCreatedAtDesc(
                        GenerationState.FAILED, "\u751f\u6210\u4efb\u52a1\u4e2d\u65ad")) {
            try {
                String imagePath = expectedLocalImagePath(record);
                if (imagePath != null && recordService.markLocalImageAvailable(record.getId(), imagePath)) {
                    restored++;
                }
            } catch (Exception e) {
                log.error("Failed to reconcile late generation image: recordId={}", record.getId(), e);
            }
        }
        if (restored > 0) {
            log.warn("Restored late generation images: count={}, trigger={}", restored, trigger);
        }
    }

    private String expectedLocalImagePath(GenerationRecord record) {
        String fileName = record.getTaskCode() + ".png";
        if (record.getUserEmail() != null && !record.getUserEmail().isBlank()) {
            String ownerFolder = authService.safeEmailFolder(record.getUserEmail());
            String ownedPath = ownerFolder + "/" + fileName;
            if (storageConfig.resolveReadableImageFile(ownedPath) != null) {
                return ownedPath;
            }
        }
        return storageConfig.resolveReadableImageFile(fileName) != null ? fileName : null;
    }
}
