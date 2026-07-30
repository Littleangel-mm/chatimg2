package com.example.chatseedance2.service;

import com.example.chatseedance2.config.VideoProperties;
import com.example.chatseedance2.dto.UploadedVideoAssetResponse;
import com.example.chatseedance2.entity.UserAccount;
import com.example.chatseedance2.entity.VideoAssetMediaType;
import com.example.chatseedance2.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VideoStorageService {

    private static final Map<VideoAssetMediaType, Set<String>> MEDIA_TYPES = Map.of(
            VideoAssetMediaType.IMAGE, Set.of("image/png", "image/jpeg", "image/webp"),
            VideoAssetMediaType.VIDEO, Set.of("video/mp4", "video/webm", "video/quicktime"),
            VideoAssetMediaType.AUDIO, Set.of("audio/mpeg", "audio/mp4", "audio/wav", "audio/x-wav", "audio/ogg"));

    private final VideoProperties properties;
    private Path root;

    @PostConstruct
    void initialize() {
        try {
            root = Paths.get(properties.storagePath()).toAbsolutePath().normalize();
            Files.createDirectories(root.resolve("inputs"));
            Files.createDirectories(root.resolve("results"));
        } catch (IOException e) {
            throw new IllegalStateException("Create video storage directory failed", e);
        }
    }

    public UploadedVideoAssetResponse storeInput(MultipartFile file, VideoAssetMediaType mediaType,
                                                  UserAccount user, String requestBaseUrl) {
        validateUpload(file, mediaType);
        String mimeType = normalizeMimeType(file.getContentType());
        String relativePath = "inputs/" + safeOwner(user) + "/" + UUID.randomUUID() + extension(mimeType);
        Path target = resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException e) {
            throw new BusinessException("ASSET_SAVE_FAILED", "素材保存失败，请重试", e);
        }
        return new UploadedVideoAssetResponse(
                mediaType,
                relativePath,
                publicUrl(relativePath, requestBaseUrl),
                safeFilename(file.getOriginalFilename()),
                mimeType,
                file.getSize());
    }

    public String requireOwnedPublicUrl(String localPath, UserAccount user) {
        String normalized = normalizeRelativePath(localPath);
        String ownerPrefix = "inputs/" + safeOwner(user) + "/";
        if (!normalized.startsWith(ownerPrefix) || !Files.isRegularFile(resolve(normalized))) {
            throw new BusinessException("ASSET_NOT_FOUND", "参考素材不存在或无权访问");
        }
        String baseUrl = properties.publicBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BusinessException("PUBLIC_BASE_URL_REQUIRED", "服务器尚未配置素材公网地址");
        }
        return publicUrl(normalized, baseUrl);
    }

    public String resourceLocation() {
        return root.toUri().toString();
    }

    public Path resolve(String relativePath) {
        Path path = root.resolve(normalizeRelativePath(relativePath)).normalize();
        if (!path.startsWith(root)) {
            throw new BusinessException("INVALID_ASSET_PATH", "素材路径无效");
        }
        return path;
    }

    private void validateUpload(MultipartFile file, VideoAssetMediaType mediaType) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("ASSET_EMPTY", "请选择素材文件");
        }
        String mimeType = normalizeMimeType(file.getContentType());
        if (!MEDIA_TYPES.getOrDefault(mediaType, Set.of()).contains(mimeType)) {
            throw new BusinessException("ASSET_TYPE_INVALID", "不支持该素材格式");
        }
        long maxBytes = switch (mediaType) {
            case IMAGE -> properties.maxImageBytes();
            case VIDEO -> properties.maxVideoBytes();
            case AUDIO -> properties.maxAudioBytes();
        };
        if (file.getSize() > maxBytes) {
            throw new BusinessException("ASSET_TOO_LARGE", "素材文件过大");
        }
    }

    private String normalizeMimeType(String contentType) {
        if (contentType == null) {
            return "application/octet-stream";
        }
        return contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private String extension(String mimeType) {
        return switch (mimeType) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "video/mp4" -> ".mp4";
            case "video/webm" -> ".webm";
            case "video/quicktime" -> ".mov";
            case "audio/mpeg" -> ".mp3";
            case "audio/mp4" -> ".m4a";
            case "audio/wav", "audio/x-wav" -> ".wav";
            case "audio/ogg" -> ".ogg";
            default -> throw new BusinessException("ASSET_TYPE_INVALID", "不支持该素材格式");
        };
    }

    private String publicUrl(String relativePath, String requestBaseUrl) {
        String configured = properties.publicBaseUrl();
        String base = configured == null || configured.isBlank() ? requestBaseUrl : configured;
        if (base == null || base.isBlank()) {
            throw new BusinessException("PUBLIC_BASE_URL_REQUIRED", "无法确定素材访问地址");
        }
        String encoded = String.join("/", java.util.Arrays.stream(relativePath.split("/"))
                .map(part -> URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"))
                .toList());
        return base.replaceFirst("/+$", "") + "/video-media/" + encoded;
    }

    private String normalizeRelativePath(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("INVALID_ASSET_PATH", "素材路径不能为空");
        }
        return value.replace('\\', '/').replaceFirst("^/+", "");
    }

    private String safeOwner(UserAccount user) {
        String email = user.getEmail() == null ? "" : user.getEmail().trim().toLowerCase(Locale.ROOT);
        if (!email.matches("^[A-Za-z0-9._%+@-]+$")) {
            throw new BusinessException("INVALID_USER_FOLDER", "用户素材目录无效");
        }
        return email;
    }

    private String safeFilename(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String value = Paths.get(name).getFileName().toString();
        return value.length() > 255 ? value.substring(0, 255) : value;
    }
}
