package com.example.chatseedance2.service;

import com.example.chatseedance2.entity.VideoAssetMediaType;
import com.example.chatseedance2.entity.VideoAssetRole;
import com.example.chatseedance2.entity.VideoGenerationType;

import java.util.List;
import java.util.Map;

public record PreparedVideoRequest(
        String prompt,
        String model,
        int durationSeconds,
        String aspectRatio,
        String resolution,
        String clientMessageId,
        String requestFingerprint,
        VideoGenerationType generationType,
        List<Asset> assets,
        Map<String, Object> providerPayload
) {
    public record Asset(
            VideoAssetMediaType mediaType,
            VideoAssetRole role,
            String sourceUrl,
            String localPath,
            int sortOrder
    ) {
    }
}
