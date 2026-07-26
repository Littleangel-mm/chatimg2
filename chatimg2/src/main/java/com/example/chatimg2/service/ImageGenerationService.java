package com.example.chatimg2.service;

import com.example.chatimg2.config.ImageStorageConfig;
import com.example.chatimg2.entity.ActivationKey;
import com.example.chatimg2.entity.GenerationRecord;
import com.example.chatimg2.entity.UserAccount;
import com.example.chatimg2.exception.BusinessException;
import com.example.chatimg2.exception.ImageDownloadException;
import com.example.chatimg2.repository.GenerationRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.Base64;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImageGenerationService {

    private final KeyService keyService;
    private final GenerationRecordRepository recordRepository;
    private final GenerationRecordService recordService;
    private final AsyncGenerationExecutor asyncGenerationExecutor;
    private final ImageResultDeliveryService imageResultDeliveryService;
    private final GenerationRecordViewService recordViewService;
    private final ImageStorageConfig storageConfig;
    private final AuthService authService;

    private static final int MAX_SOURCE_IMAGE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_SOURCE_IMAGE_BASE64_CHARS = 28 * 1024 * 1024;
    private static final int MAX_PROMPT_CHARS = 10_000;

    public GenerationRecord submitGeneration(String keyCode, String prompt, String type,
                                             String sourceImageBase64, String clientMessageId) {
        String normalizedPrompt = validatePrompt(prompt);
        ActivationKey key = keyService.activate(keyCode)
                .orElseThrow(() -> new BusinessException("KEY_INVALID", "密钥无效或已禁用"));

        GenerationRecord existing = findExistingClientRecord(key.getId(), null, clientMessageId);
        if (existing != null) {
            return prepareRecordForRead(existing);
        }

        String generationType = resolveGenerationType(type);
        String sourceImagePath = saveSourceIfNeeded(generationType, sourceImageBase64, null);

        GenerationRecordService.PendingRecord pending;
        try {
            pending = recordService.createPendingRecord(
                    key.getId(), normalizedPrompt, generationType, sourceImagePath, clientMessageId);
        } catch (RuntimeException e) {
            deleteUnusedSourceImage(sourceImagePath, null);
            throw e;
        }
        if (pending.created()) {
            GenerationRecord record = pending.record();
            scheduleGeneration(record, key.getId(), null, normalizedPrompt, generationType, sourceImagePath, null);
        } else {
            deleteUnusedSourceImage(sourceImagePath, pending.record().getSourceImagePath());
        }
        return prepareRecordForRead(pending.record());
    }

    public GenerationRecord submitGenerationForUser(UserAccount user, String prompt, String type,
                                                    String sourceImageBase64, String clientMessageId) {
        String normalizedPrompt = validatePrompt(prompt);
        GenerationRecord existing = findExistingClientRecord(null, user.getId(), clientMessageId);
        if (existing != null) {
            return prepareRecordForRead(existing);
        }

        String ownerFolder = authService.safeEmailFolder(user.getEmail());
        String generationType = resolveGenerationType(type);
        String sourceImagePath = saveSourceIfNeeded(generationType, sourceImageBase64, ownerFolder);

        GenerationRecordService.PendingRecord pending;
        try {
            pending = recordService.createPendingRecordForUser(
                    user, normalizedPrompt, generationType, sourceImagePath, clientMessageId);
        } catch (RuntimeException e) {
            deleteUnusedSourceImage(sourceImagePath, null);
            throw e;
        }
        if (pending.created()) {
            GenerationRecord record = pending.record();
            scheduleGeneration(record, null, user.getId(), normalizedPrompt,
                    generationType, sourceImagePath, ownerFolder);
        } else {
            deleteUnusedSourceImage(sourceImagePath, pending.record().getSourceImagePath());
        }
        return prepareRecordForRead(pending.record());
    }

    public GenerationRecord getRecordStatus(String keyCode, Integer recordId) {
        ActivationKey key = keyService.getByKeyCode(keyCode)
                .orElseThrow(() -> new BusinessException("KEY_NOT_FOUND", "密钥不存在"));
        GenerationRecord record = recordRepository.findById(recordId)
                .orElseThrow(() -> new BusinessException("RECORD_NOT_FOUND", "记录不存在"));
        if (record.getKeyId() == null || !record.getKeyId().equals(key.getId())) {
            throw new BusinessException("RECORD_FORBIDDEN", "无权查看此记录");
        }
        return prepareRecordForRead(record);
    }

    public GenerationRecord getRecordStatusForUser(UserAccount user, Integer recordId) {
        GenerationRecord record = recordRepository.findById(recordId)
                .orElseThrow(() -> new BusinessException("RECORD_NOT_FOUND", "记录不存在"));
        if (!user.getId().equals(record.getUserId())) {
            throw new BusinessException("RECORD_FORBIDDEN", "无权查看此记录");
        }
        return prepareRecordForRead(record);
    }

    public String getRemoteDisplayUrl(String keyCode, Integer recordId) {
        return requireRemoteDisplayUrl(getRecordStatus(keyCode, recordId));
    }

    public String getRemoteDisplayUrlForUser(UserAccount user, Integer recordId) {
        return requireRemoteDisplayUrl(getRecordStatusForUser(user, recordId));
    }

    public void retryRemoteImageBackup(String keyCode, Integer recordId) {
        GenerationRecord record = getRecordStatus(keyCode, recordId);
        if (record.isRemoteImageAvailable()) {
            imageResultDeliveryService.retrySavedRemoteImage(recordId);
        }
    }

    public void retryRemoteImageBackupForUser(UserAccount user, Integer recordId) {
        GenerationRecord record = getRecordStatusForUser(user, recordId);
        if (record.isRemoteImageAvailable()) {
            imageResultDeliveryService.retrySavedRemoteImage(recordId);
        }
    }

    public void saveBrowserImageBackup(String keyCode, Integer recordId, byte[] imageBytes) {
        getRecordStatus(keyCode, recordId);
        imageResultDeliveryService.saveBrowserDeliveredImage(recordId, imageBytes);
    }

    public void saveBrowserImageBackupForUser(UserAccount user, Integer recordId, byte[] imageBytes) {
        getRecordStatusForUser(user, recordId);
        imageResultDeliveryService.saveBrowserDeliveredImage(recordId, imageBytes);
    }

    public List<GenerationRecord> getHistory(String keyCode) {
        ActivationKey key = keyService.getByKeyCode(keyCode)
                .orElseThrow(() -> new BusinessException("KEY_NOT_FOUND", "密钥不存在"));
        return recordRepository.findTop30ByKeyIdOrderByCreatedAtDesc(key.getId()).stream()
                .map(this::prepareRecordForRead)
                .toList();
    }

    public List<GenerationRecord> getHistoryForUser(UserAccount user) {
        return recordRepository.findTop30ByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(this::prepareRecordForRead)
                .toList();
    }

    public List<GenerationRecord> getAdminHistory() {
        return recordRepository.findTop100ByOrderByCreatedAtDesc().stream()
                .map(this::prepareRecordForRead)
                .toList();
    }

    private GenerationRecord prepareRecordForRead(GenerationRecord record) {
        return recordViewService.prepare(record);
    }

    private String requireRemoteDisplayUrl(GenerationRecord record) {
        String remoteUrl = record == null ? null : record.getRemoteImageUrl();
        if (!GenerationState.isCompleted(record == null ? null : record.getStatus()) || !isHttpUrl(remoteUrl)) {
            throw new BusinessException("REMOTE_IMAGE_UNAVAILABLE", "远程图片结果暂不可用");
        }
        return remoteUrl;
    }

    private boolean isHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String resolveGenerationType(String type) {
        return "img2img".equals(type) ? "img2img" : "text2img";
    }

    private GenerationRecord findExistingClientRecord(Integer keyId, Integer userId, String clientMessageId) {
        if (clientMessageId == null || clientMessageId.isBlank()) {
            return null;
        }
        String normalized = clientMessageId.trim();
        if (userId != null) {
            return recordRepository.findFirstByUserIdAndClientMessageIdOrderByCreatedAtDesc(userId, normalized)
                    .orElse(null);
        }
        if (keyId != null) {
            return recordRepository.findFirstByKeyIdAndClientMessageIdOrderByCreatedAtDesc(keyId, normalized)
                    .orElse(null);
        }
        return null;
    }

    private String saveSourceIfNeeded(String generationType, String sourceImageBase64, String ownerFolder) {
        if (!"img2img".equals(generationType)) {
            return null;
        }
        if (sourceImageBase64 == null || sourceImageBase64.isBlank()) {
            throw new BusinessException("SOURCE_IMAGE_REQUIRED", "图生图需要上传参考图片");
        }
        return saveSourceImage(decodeBase64Image(sourceImageBase64), ownerFolder);
    }

    private DecodedImage decodeBase64Image(String base64) {
        if (base64.length() > MAX_SOURCE_IMAGE_BASE64_CHARS) {
            throw new BusinessException("SOURCE_IMAGE_TOO_LARGE", "参考图片不能超过 20MB");
        }
        String data = base64;
        if (base64.contains(",")) {
            data = base64.substring(base64.indexOf(",") + 1);
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(data);
            if (bytes.length == 0) {
                throw new BusinessException("SOURCE_IMAGE_EMPTY", "参考图片内容为空");
            }
            if (bytes.length > MAX_SOURCE_IMAGE_BYTES) {
                throw new BusinessException("SOURCE_IMAGE_TOO_LARGE", "参考图片不能超过 20MB");
            }
            String mediaType = ImageContentSupport.detectMediaType(bytes);
            if (mediaType == null) {
                throw new BusinessException("SOURCE_IMAGE_INVALID", "仅支持 PNG、JPEG 和 WebP 图片");
            }
            return new DecodedImage(bytes, ImageContentSupport.extension(mediaType));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("SOURCE_IMAGE_INVALID", "参考图片格式无效", e);
        }
    }

    private String validatePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException("PROMPT_REQUIRED", "请输入提示词");
        }
        String normalized = prompt.trim();
        if (normalized.length() > MAX_PROMPT_CHARS) {
            throw new BusinessException("PROMPT_TOO_LONG", "提示词不能超过 10000 个字符");
        }
        return normalized;
    }

    private String saveSourceImage(DecodedImage image, String ownerFolder) {
        try {
            String fileName = "src_" + java.util.UUID.randomUUID() + "." + image.extension();
            Path dir = ownerFolder == null || ownerFolder.isBlank()
                    ? storageConfig.getSourcesDir()
                    : storageConfig.getSourcesDir().resolve(ownerFolder);
            Files.createDirectories(dir);
            Path filePath = dir.resolve(fileName);
            Files.write(filePath, image.bytes());
            return ownerFolder == null || ownerFolder.isBlank()
                    ? "sources/" + fileName
                    : "sources/" + ownerFolder + "/" + fileName;
        } catch (IOException e) {
            throw new ImageDownloadException("保存参考图失败", e);
        }
    }

    private void deleteUnusedSourceImage(String sourceImagePath, String retainedSourceImagePath) {
        if (sourceImagePath == null || sourceImagePath.equals(retainedSourceImagePath)) {
            return;
        }
        try {
            Files.deleteIfExists(storageConfig.resolveSourceImageFile(sourceImagePath));
        } catch (Exception ignored) {
            // An unused upload is harmless and can be cleaned by normal storage maintenance.
        }
    }

    private void scheduleGeneration(GenerationRecord record, Integer keyId, Integer userId, String prompt,
                                    String generationType, String sourceImagePath, String ownerFolder) {
        try {
            asyncGenerationExecutor.runGeneration(
                    record.getId(), record.getTaskCode(), keyId, userId,
                    prompt, generationType, sourceImagePath, ownerFolder);
        } catch (TaskRejectedException e) {
            log.error("Generation queue rejected task: recordId={}, taskCode={}",
                    record.getId(), record.getTaskCode(), e);
            if (userId != null) {
                recordService.markFailedForUser(record.getId(), userId, "生成任务繁忙，请稍后重试");
            } else {
                recordService.markFailed(record.getId(), keyId, "生成任务繁忙，请稍后重试");
            }
            deleteUnusedSourceImage(sourceImagePath, null);
            throw new BusinessException("GENERATION_QUEUE_FULL", "当前生成任务较多，请稍后重试", e);
        }
    }

    private record DecodedImage(byte[] bytes, String extension) {
    }
}
