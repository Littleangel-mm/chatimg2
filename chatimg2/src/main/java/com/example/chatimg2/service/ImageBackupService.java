package com.example.chatimg2.service;

import com.example.chatimg2.config.ImageStorageConfig;
import com.example.chatimg2.exception.ImageDownloadException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.net.URI;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageBackupService {

    private final ImageStorageConfig storageConfig;

    @Value("${app.image.download.max-bytes:20971520}")
    private long maxImageBytes;

    @Value("${app.image.download.max-attempts:3}")
    private int maxDownloadAttempts;

    @Value("${app.image.download.retry-delay-ms:1000}")
    private long downloadRetryDelayMs;

    @Value("${app.image.download.connect-timeout-ms:30000}")
    private long downloadConnectTimeoutMs;

    @Value("${app.image.download.read-timeout-ms:120000}")
    private long downloadReadTimeoutMs;

    private OkHttpClient httpClient;

    @PostConstruct
    void initHttpClient() {
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(Math.max(1000L, downloadConnectTimeoutMs), TimeUnit.MILLISECONDS)
                .readTimeout(Math.max(1000L, downloadReadTimeoutMs), TimeUnit.MILLISECONDS)
                .build();
    }

    public record BackupResult(String imagePath) {}

    public String saveBase64(String b64) {
        Path filePath = storageConfig.getBackupDir().resolve(UUID.randomUUID() + ".png");
        Path tempPath = tempPath(filePath);
        try {
            byte[] bytes = Base64.getDecoder().decode(b64);
            if (!ImageContentSupport.isSupported(bytes)) {
                throw new ImageDownloadException("Base64 result is not a supported image");
            }
            validateImageSize(bytes.length);
            Files.write(tempPath, bytes);
            moveCompletedFile(tempPath, filePath);
            return filePath.getFileName().toString();
        } catch (IllegalArgumentException | IOException e) {
            deleteQuietly(tempPath);
            throw new ImageDownloadException("保存 Base64 图片失败: " + e.getMessage(), e);
        }
    }

    public BackupResult backupSync(String imageUrl, String taskCode, String ownerFolder) {
        Path target = targetPath(taskCode, ownerFolder);
        saveToImgDir(imageUrl, target);
        String imagePath = relativeImagePath(taskCode, ownerFolder);
        log.info("Saved generated image to img/{}", imagePath);
        return new BackupResult(imagePath);
    }

    public BackupResult backupBytes(byte[] imageBytes, String taskCode, String ownerFolder) {
        if (!ImageContentSupport.isSupported(imageBytes)) {
            throw new ImageDownloadException("Invalid generated image content");
        }
        validateImageSize(imageBytes.length);
        Path target = targetPath(taskCode, ownerFolder);
        Path tempPath = target.resolveSibling(target.getFileName() + ".upload-" + UUID.randomUUID() + ".part");
        try {
            Files.write(tempPath, imageBytes);
            moveCompletedFile(tempPath, target);
            String imagePath = relativeImagePath(taskCode, ownerFolder);
            log.info("Saved browser-delivered image to img/{}", imagePath);
            return new BackupResult(imagePath);
        } catch (IOException e) {
            deleteQuietly(tempPath);
            throw new ImageDownloadException("Failed to save browser-delivered image", e);
        }
    }

    private void saveToImgDir(String imageUrl, Path target) {
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new ImageDownloadException("图片地址为空，无法保存");
        }

        if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) {
            downloadToImgDir(imageUrl, target);
            return;
        }

        Path existing = resolveExistingPath(imageUrl);
        if (existing != null && Files.exists(existing)) {
            copyToTarget(existing, target);
            return;
        }

        throw new ImageDownloadException("无法找到待保存的图片: " + imageUrl);
    }

    private Path resolveExistingPath(String imageUrl) {
        if (imageUrl.startsWith("/api/images/") || imageUrl.startsWith("/img/")) {
            return storageConfig.resolveReadableImageFile(imageUrl);
        }
        if (!imageUrl.contains("/") && !imageUrl.startsWith("http")) {
            return storageConfig.getBackupDir().resolve(imageUrl);
        }
        return null;
    }

    private void copyToTarget(Path source, Path target) {
        Path tempPath = tempPath(target);
        try {
            if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
                return;
            }
            validateImageSize(Files.size(source));
            Files.copy(source, tempPath, StandardCopyOption.REPLACE_EXISTING);
            moveCompletedFile(tempPath, target);
            if (isTempIntermediateFile(source)) {
                Files.deleteIfExists(source);
                log.debug("Removed temp image {}", source.getFileName());
            }
        } catch (IOException e) {
            deleteQuietly(tempPath);
            throw new ImageDownloadException("复制图片失败: " + e.getMessage(), e);
        }
    }

    private boolean isTempIntermediateFile(Path source) {
        if (source == null || source.getFileName() == null) {
            return false;
        }
        String name = source.getFileName().toString();
        return name.matches("[0-9a-fA-F\\-]{36}\\.png")
                && source.getParent() != null
                && source.getParent().equals(storageConfig.getBackupDir());
    }

    private Path targetPath(String taskCode, String ownerFolder) {
        Path backupRoot = storageConfig.getBackupDir().toAbsolutePath().normalize();
        Path dir = ownerFolder == null || ownerFolder.isBlank()
                ? backupRoot
                : backupRoot.resolve(ownerFolder).normalize();
        if (!dir.startsWith(backupRoot)) {
            throw new ImageDownloadException("图片保存目录越界: " + ownerFolder);
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new ImageDownloadException("创建图片目录失败: " + e.getMessage(), e);
        }
        Path target = dir.resolve(taskCode + ".png").normalize();
        if (!target.startsWith(dir)) {
            throw new ImageDownloadException("图片文件名越界: " + taskCode);
        }
        return target;
    }

    private String relativeImagePath(String taskCode, String ownerFolder) {
        return ownerFolder == null || ownerFolder.isBlank()
                ? taskCode + ".png"
                : ownerFolder + "/" + taskCode + ".png";
    }

    private void downloadToImgDir(String imageUrl, Path target) {
        ImageDownloadException lastError = null;
        String resultHost = resultHost(imageUrl);
        int attempts = Math.max(1, maxDownloadAttempts);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            Path tempPath = tempPath(target);
            try {
                Request request = new Request.Builder().url(imageUrl).build();
                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        long contentLength = response.body().contentLength();
                        if (contentLength > 0) {
                            validateImageSize(contentLength);
                        }
                        try (InputStream is = response.body().byteStream()) {
                            copyWithLimit(is, tempPath);
                        }
                        validateImageFile(tempPath);
                        moveCompletedFile(tempPath, target);
                        return;
                    }
                    lastError = new ImageDownloadException("下载图片失败: HTTP " + response.code());
                    if (!isRetryableDownloadStatus(response.code())) {
                        throw lastError;
                    }
                    log.warn("Download attempt {}/{} failed: host={}, status={}",
                            attempt, attempts, resultHost, response.code());
                }
            } catch (ImageDownloadException e) {
                deleteQuietly(tempPath);
                lastError = e;
                if (e.getCause() == null) {
                    throw e;
                }
                log.warn("Download attempt {}/{} failed: host={}, reason={}",
                        attempt, attempts, resultHost, e.getMessage());
            } catch (IOException e) {
                deleteQuietly(tempPath);
                lastError = new ImageDownloadException("下载图片网络异常: " + e.getMessage(), e);
                log.warn("Download attempt {}/{} failed: host={}, reason={}",
                        attempt, attempts, resultHost, e.getMessage());
            }
            if (attempt < attempts) {
                sleepBeforeDownloadRetry(attempt);
            }
        }
        if (lastError != null) {
            throw new ImageDownloadException("图片下载重试后仍失败", lastError);
        }
        throw new ImageDownloadException("图片下载失败，未获得有效响应");
    }

    private void sleepBeforeDownloadRetry(int attempt) {
        long delay = Math.max(0, downloadRetryDelayMs) * attempt;
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImageDownloadException("图片下载重试被中断", e);
        }
    }

    private String resultHost(String imageUrl) {
        try {
            String host = URI.create(imageUrl).getHost();
            return host == null || host.isBlank() ? "unknown" : host;
        } catch (IllegalArgumentException e) {
            return "invalid";
        }
    }

    private void copyWithLimit(InputStream inputStream, Path target) throws IOException {
        long total = 0;
        byte[] buffer = new byte[8192];
        try (OutputStream outputStream = Files.newOutputStream(target)) {
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                total += read;
                validateImageSize(total);
                outputStream.write(buffer, 0, read);
            }
        }
    }

    private void validateImageSize(long size) {
        if (size <= 0) {
            throw new ImageDownloadException("图片内容为空");
        }
        if (size > maxImageBytes) {
            throw new ImageDownloadException("图片超过允许大小: " + size + " bytes");
        }
    }

    private void validateImageFile(Path path) throws IOException {
        byte[] signature;
        try (InputStream input = Files.newInputStream(path)) {
            signature = input.readNBytes(12);
        }
        if (!ImageContentSupport.isSupported(signature)) {
            throw new IOException("下载结果不是支持的图片格式");
        }
    }

    private boolean isRetryableDownloadStatus(int statusCode) {
        return statusCode == 408 || statusCode == 425 || statusCode == 429
                || statusCode == 500 || statusCode == 502 || statusCode == 503
                || statusCode == 504 || statusCode == 524;
    }

    private Path tempPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".part");
    }

    private void moveCompletedFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("Delete temp image failed: {}", path, e);
        }
    }
}
