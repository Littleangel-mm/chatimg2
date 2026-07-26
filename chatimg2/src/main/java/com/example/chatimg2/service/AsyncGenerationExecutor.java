package com.example.chatimg2.service;

import com.example.chatimg2.config.ImageStorageConfig;
import com.example.chatimg2.exception.ApiCallException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncGenerationExecutor {

    private final ImageApiClient imageApiClient;
    private final ImageBackupService imageBackupService;
    private final ImageResultDeliveryService imageResultDeliveryService;
    private final GenerationRecordService recordService;
    private final ImageStorageConfig storageConfig;
    private final DistributedLockService distributedLockService;

    @Value("${app.generation.lock.ttl-ms:1800000}")
    private long generationLockTtlMs;

    @Value("${app.api.model.text2img:gpt-image-2}")
    private String text2imgModel;

    @Value("${app.api.model.img2img:gpt-image-2}")
    private String img2imgModel;

    private static final String CONTENT_POLICY_ADVICE_PREFIX = "CONTENT_POLICY_ADVICE:";
    private static final String IMG2IMG_USER_ERROR = "生成失败，请更换图片重试";
    private static final String TEXT2IMG_USER_ERROR = "生成失败，请稍后重试";

    @Async("generationExecutor")
    public void runGeneration(Integer recordId, String taskCode, Integer keyId, Integer userId, String prompt,
                              String generationType, String sourceImagePath, String ownerFolder) {
        String lockKey = "generation:record:" + recordId;
        Optional<DistributedLockService.LockToken> lock;
        try {
            lock = distributedLockService.tryAcquire(lockKey, Duration.ofMillis(generationLockTtlMs));
            if (lock.isEmpty()) {
                log.warn("Async generation skipped because lock is held: recordId={}, taskCode={}", recordId, taskCode);
                return;
            }
        } catch (Exception e) {
            log.error("Async generation failed before start: recordId={}, taskCode={}", recordId, taskCode, e);
            markGenerationFailed(recordId, keyId, userId, generationType, e);
            return;
        }
        long startedAt = System.currentTimeMillis();
        DistributedLockService.LockLease lease = null;
        try {
            lease = distributedLockService.keepAlive(
                    lock.orElseThrow(), Duration.ofMillis(generationLockTtlMs));
            log.info("Async generation started: recordId={}, taskCode={}, type={}",
                    recordId, taskCode, generationType);
            runLockedGeneration(recordId, taskCode, prompt, generationType, sourceImagePath, ownerFolder, startedAt);
        } catch (Exception e) {
            log.error("Async generation failed: recordId={}, taskCode={}", recordId, taskCode, e);
            markGenerationFailed(recordId, keyId, userId, generationType, e);
        } finally {
            if (lease != null) {
                lease.close();
            }
            distributedLockService.release(lock.orElse(null));
        }
    }

    private void runLockedGeneration(Integer recordId, String taskCode, String prompt, String generationType,
                                     String sourceImagePath, String ownerFolder, long startedAt) throws Exception {
        String imageUrl;
        if ("img2img".equals(generationType)) {
            Path sourcePath = resolveSourcePath(sourceImagePath);
            byte[] imageBytes = Files.readAllBytes(sourcePath);
            String mediaType = ImageContentSupport.detectMediaType(sourcePath, imageBytes);
            imageUrl = imageApiClient.imageEdit(
                    prompt,
                    imageBytes,
                    mediaType,
                    sourcePath.getFileName().toString(),
                    img2imgModel,
                    List.of());
        } else {
            imageUrl = imageApiClient.textToImage(prompt, text2imgModel, List.of());
        }

        if (isRemoteUrl(imageUrl)) {
            if (recordService.markRemoteImageAvailable(recordId, imageUrl)) {
                try {
                    imageResultDeliveryService.retrySavedRemoteImage(recordId);
                } catch (TaskRejectedException e) {
                    log.warn("Image backup queue is full; remote result remains available: recordId={}, taskCode={}",
                            recordId, taskCode);
                }
            }
        } else {
            ImageBackupService.BackupResult backup = imageBackupService.backupSync(imageUrl, taskCode, ownerFolder);
            recordService.markLocalImageAvailable(recordId, backup.imagePath());
        }
        log.info("Async generation completed: recordId={}, taskCode={}, elapsedMs={}",
                recordId, taskCode, System.currentTimeMillis() - startedAt);
    }

    private boolean isRemoteUrl(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }

    private void markGenerationFailed(Integer recordId, Integer keyId, Integer userId, String generationType, Throwable error) {
        String userMessage = resolveUserMessage(generationType, error);
        try {
            if (userId != null) {
                recordService.markFailedForUser(recordId, userId, userMessage);
            } else {
                recordService.markFailed(recordId, keyId, userMessage);
            }
        } catch (Exception persistenceError) {
            log.error("Failed to persist generation failure: recordId={}", recordId, persistenceError);
        }
    }

    private String resolveUserMessage(String generationType, Throwable error) {
        String contentPolicyMessage = extractContentPolicyMessage(error);
        if (contentPolicyMessage != null) {
            return contentPolicyMessage;
        }
        return "img2img".equals(generationType) ? IMG2IMG_USER_ERROR : TEXT2IMG_USER_ERROR;
    }

    private String extractContentPolicyMessage(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ApiCallException apiError) {
                ImageApiErrorClassifier.ApiErrorDetails details = new ImageApiErrorClassifier.ApiErrorDetails(
                        apiError.getUserMessage(), apiError.getApiErrorType(), apiError.getApiErrorCode());
                if (ImageApiErrorClassifier.isContentPolicyError(details)
                        && apiError.getUserMessage() != null
                        && !apiError.getUserMessage().isBlank()) {
                    return CONTENT_POLICY_ADVICE_PREFIX + apiError.getUserMessage().trim();
                }
            }
            String message = current.getMessage();
            String advice = extractAdviceFromMessage(message);
            if (advice != null) {
                return CONTENT_POLICY_ADVICE_PREFIX + advice;
            }
            current = current.getCause();
        }
        return null;
    }

    private String extractAdviceFromMessage(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        int markerIndex = message.indexOf(CONTENT_POLICY_ADVICE_PREFIX);
        if (markerIndex >= 0) {
            String cleaned = message.substring(markerIndex + CONTENT_POLICY_ADVICE_PREFIX.length()).trim();
            return cleaned.isBlank() ? null : cleaned;
        }

        String legacyPrefix = "内容安全提示：";
        int legacyIndex = message.indexOf(legacyPrefix);
        if (legacyIndex >= 0) {
            String cleaned = message.substring(legacyIndex + legacyPrefix.length()).trim();
            return cleaned.isBlank() ? null : cleaned;
        }
        return null;
    }

    private Path resolveSourcePath(String sourceImagePath) {
        return storageConfig.resolveSourceImageFile(sourceImagePath);
    }
}
