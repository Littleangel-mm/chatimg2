package com.example.chatimg2.controller;

import com.example.chatimg2.config.ImageStorageConfig;
import com.example.chatimg2.entity.GenerationRecord;
import com.example.chatimg2.repository.GenerationRecordRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
public class ImageController {

    private final ImageStorageConfig storageConfig;
    private final GenerationRecordRepository recordRepository;

    @GetMapping("/results/{taskCode}")
    public ResponseEntity<Void> getGeneratedResult(@PathVariable String taskCode) {
        GenerationRecord record = recordRepository.findByTaskCode(taskCode).orElse(null);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }

        String localPath = record.getImagePath();
        if (localPath != null && !localPath.isBlank()) {
            URI localUri = URI.create("/img/" + encodeRelativePath(localPath));
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(localUri)
                    .cacheControl(CacheControl.noStore())
                    .build();
        }

        String remoteUrl = record.getRemoteImageUrl();
        if (!isHttpUrl(remoteUrl)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(remoteUrl))
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @GetMapping("/{filename}")
    public ResponseEntity<Resource> getImage(@PathVariable String filename) {
        return serveImage(filename);
    }

    @GetMapping("/**")
    public ResponseEntity<Resource> getNestedImage(HttpServletRequest request) {
        String path = String.valueOf(request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE));
        String prefix = "/api/images/";
        if (path.startsWith(prefix)) {
            path = path.substring(prefix.length());
        }
        return serveImage(path);
    }

    private ResponseEntity<Resource> serveImage(String path) {
        try {
            Path filePath = storageConfig.resolveReadableImageFile(path);
            if (filePath == null) {
                return ResponseEntity.notFound().build();
            }
            Resource resource = new UrlResource(filePath.toUri());

            if (resource.exists() && resource.isReadable()) {
                return ResponseEntity.ok()
                        .contentType(resolveMediaType(filePath))
                        .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic())
                        .body(resource);
            }
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    private MediaType resolveMediaType(Path filePath) {
        try {
            String type = Files.probeContentType(filePath);
            return type == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(type);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
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

    private String encodeRelativePath(String path) {
        String normalized = path.replace('\\', '/').replaceFirst("^/+", "");
        StringBuilder encoded = new StringBuilder();
        for (String segment : normalized.split("/")) {
            if (encoded.length() > 0) {
                encoded.append('/');
            }
            encoded.append(java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20"));
        }
        return encoded.toString();
    }
}
