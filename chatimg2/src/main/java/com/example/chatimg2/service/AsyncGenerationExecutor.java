package com.example.chatimg2.service;

import com.example.chatimg2.config.ImageStorageConfig;
import com.example.chatimg2.exception.ApiCallException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

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

    private static final String TEXT2IMG_MODEL = "gpt-image-2";
    private static final String IMG2IMG_MODEL = "gpt-image-2";
    private static final String CONTENT_POLICY_ADVICE_PREFIX = "CONTENT_POLICY_ADVICE:";
    private static final String IMG2IMG_USER_ERROR = "生成失败，请更换图片重试";
    private static final String TEXT2IMG_USER_ERROR = "生成失败，请稍后重试";

    @Async("generationExecutor")
    public void runGeneration(Integer recordId, String taskCode, Integer keyId, String prompt,
                              String generationType, String sourceImagePath, String model) {
        runGeneration(recordId, taskCode, keyId, null, prompt, generationType, sourceImagePath, model, null);
    }

    @Async("generationExecutor")
    public void runGeneration(Integer recordId, String taskCode, Integer keyId, Integer userId, String prompt,
                              String generationType, String sourceImagePath, String model, String ownerFolder) {
        String lockKey = "generation:record:" + recordId;
        try {
            if (!distributedLockService.acquire(lockKey, Duration.ofMillis(generationLockTtlMs))) {
                log.warn("Async generation skipped because lock is held: recordId={}, taskCode={}", recordId, taskCode);
                return;
            }
        } catch (Exception e) {
            log.error("Async generation failed before start: recordId={}, taskCode={}", recordId, taskCode, e);
            markGenerationFailed(recordId, keyId, userId, generationType, e);
            return;
        }
        long startedAt = System.currentTimeMillis();
        log.info("Async generation started: recordId={}, taskCode={}, type={}", recordId, taskCode, generationType);
        try {
            runLockedGeneration(recordId, taskCode, prompt, generationType, sourceImagePath, ownerFolder, startedAt);
        } catch (Exception e) {
            log.error("Async generation failed: recordId={}, taskCode={}", recordId, taskCode, e);
            markGenerationFailed(recordId, keyId, userId, generationType, e);
        } finally {
            distributedLockService.release(lockKey);
        }
    }

    private void runLockedGeneration(Integer recordId, String taskCode, String prompt, String generationType,
                                     String sourceImagePath, String ownerFolder, long startedAt) throws Exception {
        String imageUrl;
        if ("img2img".equals(generationType)) {
            Path sourcePath = resolveSourcePath(sourceImagePath);
            byte[] imageBytes = Files.readAllBytes(sourcePath);
            String mediaType = detectImageMediaType(sourcePath, imageBytes);
            imageUrl = imageApiClient.imageEdit(
                    prompt,
                    imageBytes,
                    mediaType,
                    sourcePath.getFileName().toString(),
                    IMG2IMG_MODEL,
                    List.of());
        } else {
            imageUrl = imageApiClient.textToImage(prompt, TEXT2IMG_MODEL, List.of());
        }

        if (isRemoteUrl(imageUrl)) {
            recordService.markRemoteImageAvailable(recordId, imageUrl);
            try {
                imageResultDeliveryService.retrySavedRemoteImage(recordId);
            } catch (Exception e) {
                log.warn("Generated image is available remotely but local backup could not be queued: "
                                + "recordId={}, taskCode={}, reason={}",
                        recordId, taskCode, e.getMessage());
            }
        } else {
            ImageBackupService.BackupResult backup = imageBackupService.backupSync(imageUrl, taskCode, ownerFolder);
            recordService.markCompleted(recordId, backup.imagePath());
        }
        log.info("Async generation completed: recordId={}, taskCode={}, elapsedMs={}",
                recordId, taskCode, System.currentTimeMillis() - startedAt);
    }

    private boolean isRemoteUrl(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }

    private void markGenerationFailed(Integer recordId, Integer keyId, Integer userId, String generationType, Throwable error) {
        String userMessage = resolveUserMessage(generationType, error);
        if (userId != null) {
            recordService.markFailedForUser(recordId, userId, userMessage);
        } else {
            recordService.markFailed(recordId, keyId, userMessage);
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
        Path path = Paths.get(sourceImagePath);
        if (path.isAbsolute()) {
            return path;
        }
        return storageConfig.getBackupDir().resolve(sourceImagePath).normalize();
    }

    private String detectImageMediaType(Path path, byte[] bytes) {
        String byMagic = detectImageMediaType(bytes);
        if (byMagic != null) {
            return byMagic;
        }
        try {
            String probed = Files.probeContentType(path);
            if (probed != null && probed.startsWith("image/")) {
                return probed;
            }
        } catch (Exception ignored) {
        }
        return "image/png";
    }

    private String detectImageMediaType(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return null;
        }
        if ((bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8) {
            return "image/jpeg";
        }
        if ((bytes[0] & 0xff) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4e
                && bytes[3] == 0x47) {
            return "image/png";
        }
        if (bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }
}
