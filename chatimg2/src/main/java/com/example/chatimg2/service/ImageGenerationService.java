package com.example.chatimg2.service;

import com.example.chatimg2.config.ImageStorageConfig;
import com.example.chatimg2.entity.ActivationKey;
import com.example.chatimg2.entity.GenerationRecord;
import com.example.chatimg2.repository.GenerationRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ImageGenerationService {

    private final KeyService keyService;
    private final GenerationRecordRepository recordRepository;
    private final GenerationRecordService recordService;
    private final AsyncGenerationExecutor asyncGenerationExecutor;
    private final ImageStorageConfig storageConfig;

    private static final int CREDITS_PER_GENERATION = 20;

    public GenerationRecord submitGeneration(String keyCode, String prompt, String type,
                                             String sourceImageBase64, String model) {
        ActivationKey key = keyService.activate(keyCode)
                .orElseThrow(() -> new RuntimeException("密钥无效或已禁用"));

        if (key.getRemainingCredits() < CREDITS_PER_GENERATION) {
            throw new RuntimeException("积分不足，剩余 " + key.getRemainingCredits());
        }

        String generationType = "img2img".equals(type) ? "img2img" : "text2img";
        if ("img2img".equals(generationType) && (sourceImageBase64 == null || sourceImageBase64.isBlank())) {
            throw new RuntimeException("图生图需要上传参考图片");
        }

        String sourceImagePath = null;
        if ("img2img".equals(generationType)) {
            byte[] img2imgBytes = decodeBase64Image(sourceImageBase64);
            sourceImagePath = saveSourceImage(img2imgBytes);
        }

        Integer keyId = key.getId();
        GenerationRecord record = recordService.createPendingRecord(
                keyId, prompt, generationType, sourceImagePath);

        asyncGenerationExecutor.runGeneration(
                record.getId(), record.getTaskCode(), keyId, prompt, generationType, sourceImagePath, model);

        return record;
    }

    public GenerationRecord getRecordStatus(String keyCode, Integer recordId) {
        ActivationKey key = keyService.getByKeyCode(keyCode)
                .orElseThrow(() -> new RuntimeException("密钥不存在"));
        GenerationRecord record = recordRepository.findById(recordId)
                .orElseThrow(() -> new RuntimeException("记录不存在"));
        if (!record.getKeyId().equals(key.getId())) {
            throw new RuntimeException("无权查看此记录");
        }
        if ("processing".equals(record.getStatus())
                && record.getImagePath() != null
                && !record.getImagePath().isBlank()) {
            record.setStatus("completed");
            record.setErrorMessage(null);
            record = recordRepository.save(record);
        }
        return record;
    }

    public List<GenerationRecord> getHistory(String keyCode) {
        ActivationKey key = keyService.getByKeyCode(keyCode)
                .orElseThrow(() -> new RuntimeException("密钥不存在"));
        return recordRepository.findTop30ByKeyIdOrderByCreatedAtDesc(key.getId()).stream()
                .filter(r -> !"failed".equals(r.getStatus()))
                .toList();
    }

    private byte[] decodeBase64Image(String base64) {
        String data = base64;
        if (base64.contains(",")) {
            data = base64.substring(base64.indexOf(",") + 1);
        }
        return Base64.getDecoder().decode(data);
    }

    private String saveSourceImage(byte[] imageBytes) {
        try {
            String fileName = "src_" + UUID.randomUUID() + ".png";
            Path filePath = storageConfig.getSourcesDir().resolve(fileName);
            Files.write(filePath, imageBytes);
            return filePath.toString();
        } catch (IOException e) {
            throw new RuntimeException("保存参考图失败", e);
        }
    }
}
