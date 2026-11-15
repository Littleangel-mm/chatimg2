package com.example.chatseedance2.dto;

import com.example.chatseedance2.entity.VideoAssetMediaType;

public record UploadedVideoAssetResponse(
        VideoAssetMediaType mediaType,
        String localPath,
        String sourceUrl,
        String originalFilename,
        String mimeType,
        long fileSize
) {
}
