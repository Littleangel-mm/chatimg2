package com.example.chatimg2.service;

import com.example.chatimg2.config.ImageStorageConfig;
import com.example.chatimg2.entity.GenerationRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

@Service
@RequiredArgsConstructor
public class GenerationRecordViewService {

    private final ImageStorageConfig storageConfig;
    private final AuthService authService;

    public GenerationRecord prepare(GenerationRecord record) {
        if (record != null) {
            record.setImagePath(resolveImagePath(record));
        }
        return record;
    }

    public String resolveImagePath(GenerationRecord record) {
        if (record == null || record.getImagePath() == null || record.getImagePath().isBlank()) {
            return null;
        }
        String imagePath = record.getImagePath();
        if (imagePath.contains("/") || imagePath.contains("\\")
                || record.getUserEmail() == null || record.getUserEmail().isBlank()) {
            return imagePath;
        }
        String ownerFolder = authService.safeEmailFolder(record.getUserEmail());
        Path ownerImage = storageConfig.getBackupDir().resolve(ownerFolder).resolve(imagePath).normalize();
        return Files.isRegularFile(ownerImage) ? ownerFolder + "/" + imagePath : imagePath;
    }
}
