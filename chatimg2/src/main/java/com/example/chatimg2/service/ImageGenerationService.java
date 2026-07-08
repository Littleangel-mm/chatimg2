package com.example.chatimg2.service;

import com.example.chatimg2.config.ImageStorageConfig;
import com.example.chatimg2.entity.ActivationKey;
import com.example.chatimg2.entity.GenerationRecord;
import com.example.chatimg2.entity.UserAccount;
import com.example.chatimg2.repository.GenerationRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ImageGenerationService {

    private final KeyService keyService;
    private final GenerationRecordRepository recordRepository;
    private final GenerationRecordService recordService;
    private final ImageApiClient imageApiClient;
    private final ImageBackupService imageBackupService;
    private final ImageStorageConfig storageConfig;
    private final AuthService authService;

    private static final int CREDITS_PER_GENERATION = 20;
    private static final List<String> TEXT2IMG_FALLBACKS = List.of(
            "gpt-image-2", "gpt-image-1", "dall-e-2", "dall-e-3"
    );

    public GenerationRecord submitGeneration(String keyCode, String prompt, String type,
                                             String sourceImageBase64, String model) {
        ActivationKey key = keyService.activate(keyCode)
                .orElseThrow(() -> new RuntimeException("密钥无效或已禁用"));

        if (key.getRemainingCredits() < CREDITS_PER_GENERATION) {
            throw new RuntimeException("积分不足，剩余 " + key.getRemainingCredits());
        }

        String generationType = resolveGenerationType(type);
        String sourceImagePath = saveSourceIfNeeded(generationType, sourceImageBase64, null);

        GenerationRecord record = recordService.createPendingRecord(key.getId(), prompt, generationType, sourceImagePath);
        try {
            completeGeneration(record, prompt, generationType, sourceImagePath, model, null);
            return recordRepository.findById(record.getId()).orElse(record);
        } catch (Exception e) {
            recordService.markFailed(record.getId(), key.getId(), e.getMessage());
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public GenerationRecord submitGenerationForUser(UserAccount user, String prompt, String type,
                                                    String sourceImageBase64, String model) {
        if (user.getRemainingCredits() < CREDITS_PER_GENERATION) {
            throw new RuntimeException("积分不足，剩余 " + user.getRemainingCredits());
        }

        String ownerFolder = authService.safeEmailFolder(user.getEmail());
        String generationType = resolveGenerationType(type);
        String sourceImagePath = saveSourceIfNeeded(generationType, sourceImageBase64, ownerFolder);

        GenerationRecord record = recordService.createPendingRecordForUser(user, prompt, generationType, sourceImagePath);
        try {
            completeGeneration(record, prompt, generationType, sourceImagePath, model, ownerFolder);
            return recordRepository.findById(record.getId()).orElse(record);
        } catch (Exception e) {
            recordService.markFailedForUser(record.getId(), user.getId(), e.getMessage());
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public GenerationRecord getRecordStatus(String keyCode, Integer recordId) {
        ActivationKey key = keyService.getByKeyCode(keyCode)
                .orElseThrow(() -> new RuntimeException("密钥不存在"));
        GenerationRecord record = recordRepository.findById(recordId)
                .orElseThrow(() -> new RuntimeException("记录不存在"));
        if (record.getKeyId() == null || !record.getKeyId().equals(key.getId())) {
            throw new RuntimeException("无权查看此记录");
        }
        return normalizeCompletedRecord(record);
    }

    public GenerationRecord getRecordStatusForUser(UserAccount user, Integer recordId) {
        GenerationRecord record = recordRepository.findById(recordId)
                .orElseThrow(() -> new RuntimeException("记录不存在"));
        if (!user.getId().equals(record.getUserId())) {
            throw new RuntimeException("无权查看此记录");
        }
        return normalizeCompletedRecord(record);
    }

    public List<GenerationRecord> getHistory(String keyCode) {
        ActivationKey key = keyService.getByKeyCode(keyCode)
                .orElseThrow(() -> new RuntimeException("密钥不存在"));
        return recordRepository.findTop30ByKeyIdOrderByCreatedAtDesc(key.getId()).stream()
                .filter(r -> !"failed".equals(r.getStatus()))
                .toList();
    }

    public List<GenerationRecord> getHistoryForUser(UserAccount user) {
        return recordRepository.findTop30ByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .filter(r -> !"failed".equals(r.getStatus()))
                .toList();
    }

    public List<GenerationRecord> getAdminHistory() {
        return recordRepository.findTop100ByOrderByCreatedAtDesc();
    }

    private void completeGeneration(GenerationRecord record, String prompt, String generationType,
                                    String sourceImagePath, String model, String ownerFolder) throws IOException {
        String imageUrl;
        if ("img2img".equals(generationType)) {
            byte[] imageBytes = Files.readAllBytes(resolveSourcePath(sourceImagePath));
            imageUrl = imageApiClient.imageEdit(prompt, imageBytes, model);
        } else {
            imageUrl = imageApiClient.textToImage(prompt, model, TEXT2IMG_FALLBACKS);
        }

        ImageBackupService.BackupResult backup = imageBackupService.backupSync(imageUrl, record.getTaskCode(), ownerFolder);
        recordService.markCompleted(record.getId(), backup.imagePath());
    }

    private GenerationRecord normalizeCompletedRecord(GenerationRecord record) {
        if ("processing".equals(record.getStatus())
                && record.getImagePath() != null
                && !record.getImagePath().isBlank()) {
            record.setStatus("completed");
            record.setErrorMessage(null);
            return recordRepository.save(record);
        }
        return record;
    }

    private String resolveGenerationType(String type) {
        return "img2img".equals(type) ? "img2img" : "text2img";
    }

    private String saveSourceIfNeeded(String generationType, String sourceImageBase64, String ownerFolder) {
        if (!"img2img".equals(generationType)) {
            return null;
        }
        if (sourceImageBase64 == null || sourceImageBase64.isBlank()) {
            throw new RuntimeException("图生图需要上传参考图片");
        }
        return saveSourceImage(decodeBase64Image(sourceImageBase64), ownerFolder);
    }

    private byte[] decodeBase64Image(String base64) {
        String data = base64;
        if (base64.contains(",")) {
            data = base64.substring(base64.indexOf(",") + 1);
        }
        return Base64.getDecoder().decode(data);
    }

    private String saveSourceImage(byte[] imageBytes, String ownerFolder) {
        try {
            String fileName = "src_" + java.util.UUID.randomUUID() + ".png";
            Path dir = ownerFolder == null || ownerFolder.isBlank()
                    ? storageConfig.getSourcesDir()
                    : storageConfig.getSourcesDir().resolve(ownerFolder);
            Files.createDirectories(dir);
            Path filePath = dir.resolve(fileName);
            Files.write(filePath, imageBytes);
            return ownerFolder == null || ownerFolder.isBlank()
                    ? filePath.toString()
                    : "sources/" + ownerFolder + "/" + fileName;
        } catch (IOException e) {
            throw new RuntimeException("保存参考图失败", e);
        }
    }

    private Path resolveSourcePath(String sourceImagePath) {
        Path path = Path.of(sourceImagePath);
        if (path.isAbsolute()) {
            return path;
        }
        return storageConfig.getBackupDir().resolve(sourceImagePath).normalize();
    }
}
