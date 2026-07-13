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
    private final AsyncGenerationExecutor asyncGenerationExecutor;
    private final ImageStorageConfig storageConfig;
    private final AuthService authService;

    private static final int CREDITS_PER_GENERATION = 20;

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
        asyncGenerationExecutor.runGeneration(
                record.getId(), record.getTaskCode(), key.getId(), null, prompt, generationType, sourceImagePath, model, null);
        return record;
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
        asyncGenerationExecutor.runGeneration(
                record.getId(), record.getTaskCode(), null, user.getId(), prompt, generationType, sourceImagePath, model, ownerFolder);
        return record;
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
                .map(this::normalizeCompletedRecord)
                .filter(r -> !"failed".equals(r.getStatus()))
                .toList();
    }

    public List<GenerationRecord> getHistoryForUser(UserAccount user) {
        return recordRepository.findTop30ByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(this::normalizeCompletedRecord)
                .filter(r -> !"failed".equals(r.getStatus()))
                .toList();
    }

    public List<GenerationRecord> getAdminHistory() {
        return recordRepository.findTop100ByOrderByCreatedAtDesc().stream()
                .map(this::normalizeCompletedRecord)
                .toList();
    }

    private GenerationRecord normalizeCompletedRecord(GenerationRecord record) {
        boolean changed = repairUserImagePath(record);
        if (record.getImagePath() != null && !record.getImagePath().isBlank()
                && (!"completed".equals(record.getStatus()) || record.getErrorMessage() != null)) {
            record.setStatus("completed");
            record.setErrorMessage(null);
            changed = true;
        }
        return changed ? recordRepository.save(record) : record;
    }

    private boolean repairUserImagePath(GenerationRecord record) {
        if (record == null
                || record.getUserEmail() == null
                || record.getUserEmail().isBlank()
                || record.getImagePath() == null
                || record.getImagePath().isBlank()
                || record.getImagePath().contains("/")
                || record.getImagePath().contains("\\")) {
            return false;
        }

        String ownerFolder = authService.safeEmailFolder(record.getUserEmail());
        Path ownerImage = storageConfig.getBackupDir().resolve(ownerFolder).resolve(record.getImagePath()).normalize();
        if (!Files.exists(ownerImage)) {
            return false;
        }
        record.setImagePath(ownerFolder + "/" + record.getImagePath());
        return true;
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

    private DecodedImage decodeBase64Image(String base64) {
        String data = base64;
        String mediaType = "image/png";
        if (base64.contains(",")) {
            String header = base64.substring(0, base64.indexOf(","));
            mediaType = parseImageMediaType(header);
            data = base64.substring(base64.indexOf(",") + 1);
        }
        return new DecodedImage(Base64.getDecoder().decode(data), mediaType, extensionForMediaType(mediaType));
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
                    ? filePath.toString()
                    : "sources/" + ownerFolder + "/" + fileName;
        } catch (IOException e) {
            throw new RuntimeException("保存参考图失败", e);
        }
    }

    private String parseImageMediaType(String dataUrlHeader) {
        String header = dataUrlHeader == null ? "" : dataUrlHeader.toLowerCase();
        if (header.contains("image/jpeg") || header.contains("image/jpg")) {
            return "image/jpeg";
        }
        if (header.contains("image/webp")) {
            return "image/webp";
        }
        if (header.contains("image/png")) {
            return "image/png";
        }
        return "image/png";
    }

    private String extensionForMediaType(String mediaType) {
        return switch (mediaType) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            default -> "png";
        };
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
        } catch (IOException ignored) {
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

    private Path resolveSourcePath(String sourceImagePath) {
        Path path = Path.of(sourceImagePath);
        if (path.isAbsolute()) {
            return path;
        }
        return storageConfig.getBackupDir().resolve(sourceImagePath).normalize();
    }

    private record DecodedImage(byte[] bytes, String mediaType, String extension) {
    }
}
