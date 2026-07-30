package com.example.chatseedance2.dto;

import com.example.chatseedance2.entity.VideoAssetMediaType;
import com.example.chatseedance2.entity.VideoAssetRole;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record VideoAssetInput(
        @NotNull(message = "素材类型不能为空") VideoAssetMediaType mediaType,
        @NotNull(message = "素材用途不能为空") VideoAssetRole role,
        @Size(max = 4000, message = "素材地址过长") String sourceUrl,
        @Size(max = 1000, message = "素材路径过长") String localPath,
        @Min(value = 0, message = "素材顺序不能小于 0")
        @Max(value = 20, message = "素材顺序不能大于 20") Integer sortOrder
) {
}
