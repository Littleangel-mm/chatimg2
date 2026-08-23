package com.example.chatseedance2.service;

import com.example.chatseedance2.dto.VideoAssetInput;
import com.example.chatseedance2.dto.VideoGenerationRequest;
import com.example.chatseedance2.entity.UserAccount;
import com.example.chatseedance2.entity.VideoAssetMediaType;
import com.example.chatseedance2.entity.VideoGenerationType;
import com.example.chatseedance2.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class VideoRequestPreparationService {

    private final VideoStorageService storageService;
    private final ObjectMapper objectMapper;
    public PreparedVideoRequest prepare(VideoGenerationRequest request, UserAccount user) {
        String prompt = request.prompt().trim();
        String model = VideoModelService.canonicalModelId(request.model());
        int duration = request.durationSeconds() == null ? 5 : request.durationSeconds();
        String aspectRatio = blankToDefault(request.aspectRatio(), "16:9");
        String resolution = blankToDefault(request.resolution(), "720p");
        String clientMessageId = normalizeOptional(request.clientMessageId(), 120);
        List<PreparedVideoRequest.Asset> assets = normalizeAssets(request.assets(), user);
        validateAssetCounts(assets);
        VideoGenerationType type = generationType(assets);
        Map<String, Object> payload = providerPayload(model, prompt, duration, aspectRatio, resolution, assets);
        return new PreparedVideoRequest(
                prompt, model, duration, aspectRatio, resolution, clientMessageId,
                fingerprint(payload), type, assets, payload);
    }

    private List<PreparedVideoRequest.Asset> normalizeAssets(List<VideoAssetInput> inputs, UserAccount user) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        List<PreparedVideoRequest.Asset> assets = new ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            VideoAssetInput input = inputs.get(index);
            String localPath = normalizeOptional(input.localPath(), 1000);
            String sourceUrl = normalizeOptional(input.sourceUrl(), 4000);
            if (localPath != null) {
                sourceUrl = storageService.requireOwnedPublicUrl(localPath, user);
            }
            if (!isHttpUrl(sourceUrl)) {
                throw new BusinessException("ASSET_URL_INVALID", "请输入供应商可直接下载的公网 HTTP 图片地址");
            }
            assets.add(new PreparedVideoRequest.Asset(
                    input.mediaType(), input.role(), sourceUrl, localPath,
                    input.sortOrder() == null ? index : input.sortOrder()));
        }
        return assets.stream()
                .sorted(Comparator.comparingInt(PreparedVideoRequest.Asset::sortOrder))
                .toList();
    }

    private void validateAssetCounts(List<PreparedVideoRequest.Asset> assets) {
        long images = count(assets, VideoAssetMediaType.IMAGE);
        long videos = count(assets, VideoAssetMediaType.VIDEO);
        long audios = count(assets, VideoAssetMediaType.AUDIO);
        if (images > 9) {
            throw new BusinessException("TOO_MANY_IMAGES", "参考图片最多 9 张");
        }
        if (videos > 3) {
            throw new BusinessException("TOO_MANY_VIDEOS", "参考视频最多 3 条");
        }
        if (audios > 3) {
            throw new BusinessException("TOO_MANY_AUDIOS", "参考音频最多 3 条");
        }
    }

    private Map<String, Object> providerPayload(String model, String prompt, int duration,
                                                String aspectRatio, String resolution,
                                                List<PreparedVideoRequest.Asset> assets) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("prompt", prompt);
        payload.put("duration", duration);
        payload.put("aspect_ratio", aspectRatio);
        payload.put("resolution", resolution);

        List<String> images = new ArrayList<>();
        List<String> videos = new ArrayList<>();
        List<String> audios = new ArrayList<>();
        for (PreparedVideoRequest.Asset asset : assets) {
            switch (asset.mediaType()) {
                case IMAGE -> {
                    images.add(asset.sourceUrl());
                }
                case VIDEO -> videos.add(asset.sourceUrl());
                case AUDIO -> audios.add(asset.sourceUrl());
            }
        }
        if (!images.isEmpty()) {
            payload.put("image_urls", images);
        }
        if (!videos.isEmpty()) {
            payload.put("video_urls", videos);
        }
        if (!audios.isEmpty()) {
            payload.put("audio_urls", audios);
        }
        return payload;
    }

    private VideoGenerationType generationType(List<PreparedVideoRequest.Asset> assets) {
        if (count(assets, VideoAssetMediaType.VIDEO) > 0) {
            return VideoGenerationType.VIDEO_TO_VIDEO;
        }
        if (count(assets, VideoAssetMediaType.IMAGE) > 0) {
            return VideoGenerationType.IMAGE_TO_VIDEO;
        }
        return VideoGenerationType.TEXT_TO_VIDEO;
    }

    private long count(List<PreparedVideoRequest.Asset> assets, VideoAssetMediaType type) {
        return assets.stream().filter(asset -> asset.mediaType() == type).count();
    }

    private String fingerprint(Map<String, Object> payload) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(payload);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception e) {
            throw new IllegalStateException("Create request fingerprint failed", e);
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

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String normalizeOptional(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }
}
