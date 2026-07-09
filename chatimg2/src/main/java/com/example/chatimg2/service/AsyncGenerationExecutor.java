package com.example.chatimg2.service;

import com.example.chatimg2.config.ImageStorageConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncGenerationExecutor {

    private final ImageApiClient imageApiClient;
    private final ImageBackupService imageBackupService;
    private final GenerationRecordService recordService;
    private final ImageStorageConfig storageConfig;

    private static final List<String> TEXT2IMG_FALLBACKS = List.of(
            "gpt-image-2", "gpt-image-1", "dall-e-2", "dall-e-3"
    );
    private static final String IMG2IMG_MODEL = "gpt-image-2";
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
        log.info("Async generation started: recordId={}, taskCode={}", recordId, taskCode);
        try {
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
                imageUrl = imageApiClient.textToImage(prompt, model, TEXT2IMG_FALLBACKS);
            }

            ImageBackupService.BackupResult backup = imageBackupService.backupSync(imageUrl, taskCode, ownerFolder);
            recordService.markCompleted(recordId, backup.imagePath());
            log.info("Async generation completed: recordId={}, taskCode={}", recordId, taskCode);
        } catch (Exception e) {
            log.error("Async generation failed: recordId={}, taskCode={}", recordId, taskCode, e);
            String userMessage = "img2img".equals(generationType) ? IMG2IMG_USER_ERROR : TEXT2IMG_USER_ERROR;
            if (userId != null) {
                recordService.markFailedForUser(recordId, userId, userMessage);
            } else {
                recordService.markFailed(recordId, keyId, userMessage);
            }
        }
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
