package com.example.chatseedance2.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record VideoGenerationRequest(
        @NotBlank(message = "请输入视频提示词")
        @Size(max = 10000, message = "提示词不能超过 10000 个字符") String prompt,
        @Size(max = 120, message = "模型名称过长") String model,
        @Min(value = 1, message = "视频时长不能小于 1 秒")
        @Max(value = 15, message = "视频时长不能超过 15 秒") Integer durationSeconds,
        @Pattern(regexp = "^(16:9|9:16|1:1|4:3|3:4|2\\.39:1)$", message = "不支持该画幅比例") String aspectRatio,
        @Pattern(regexp = "^720p$", message = "Seedance 2.0 当前仅支持 720p") String resolution,
        @Size(max = 120, message = "客户端消息 ID 过长") String clientMessageId,
        @Size(max = 15, message = "参考素材数量过多") List<@Valid VideoAssetInput> assets
) {
}
