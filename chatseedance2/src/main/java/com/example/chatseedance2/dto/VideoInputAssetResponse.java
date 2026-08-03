package com.example.chatseedance2.dto;

import com.example.chatseedance2.entity.VideoAssetMediaType;
import com.example.chatseedance2.entity.VideoAssetRole;

public record VideoInputAssetResponse(
        VideoAssetMediaType mediaType,
        VideoAssetRole role,
        String sourceUrl,
        int sortOrder
) {
}
