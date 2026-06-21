package com.example.chatimg2.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncGenerationExecutor {

    private final ImageApiClient imageApiClient;
    private final ImageBackupService imageBackupService;
    private final GenerationRecordService recordService;

    @Value("${app.api.model.img2img:gpt-image-2}")
    private String defaultImg2imgModel;

    private static final List<String> TEXT2IMG_FALLBACKS = List.of(
            "gpt-image-2", "gpt-image-1", "dall-e-2", "dall-e-3"
    );

    @Async("generationExecutor")
    public void runGeneration(Integer recordId, String taskCode, Integer keyId, String prompt,
                              String generationType, String sourceImagePath, String model) {
        log.info("Async generation started: recordId={}, taskCode={}", recordId, taskCode);
        try {
            String imageUrl;
            if ("img2img".equals(generationType)) {
                byte[] imageBytes = Files.readAllBytes(Paths.get(sourceImagePath));
                String img2imgModel = (model != null && !model.isBlank()) ? model : defaultImg2imgModel;
                imageUrl = imageApiClient.imageEdit(prompt, imageBytes, img2imgModel);
            } else {
                imageUrl = imageApiClient.textToImage(prompt, model, TEXT2IMG_FALLBACKS);
            }

            ImageBackupService.BackupResult backup = imageBackupService.backupSync(imageUrl, taskCode);
            recordService.markCompleted(recordId, backup.imagePath());
            log.info("Async generation completed: recordId={}, taskCode={}", recordId, taskCode);
        } catch (Exception e) {
            log.error("Async generation failed: recordId={}, taskCode={}", recordId, taskCode, e);
            recordService.markFailed(recordId, keyId, e.getMessage());
        }
    }
}
