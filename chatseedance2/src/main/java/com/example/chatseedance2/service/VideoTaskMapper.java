package com.example.chatseedance2.service;

import com.example.chatseedance2.dto.VideoGenerationResponse;
import com.example.chatseedance2.dto.VideoInputAssetResponse;
import com.example.chatseedance2.entity.VideoAssetDirection;
import com.example.chatseedance2.entity.VideoGenerationAsset;
import com.example.chatseedance2.entity.VideoGenerationTask;
import com.example.chatseedance2.repository.VideoGenerationAssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class VideoTaskMapper {

    private final VideoGenerationAssetRepository assetRepository;

    public VideoGenerationResponse toResponse(VideoGenerationTask task) {
        List<VideoGenerationAsset> assets = assetRepository.findByTaskIdOrderBySortOrderAsc(task.getId());
        List<VideoInputAssetResponse> inputAssets = assets.stream()
                .filter(asset -> asset.getDirection() == VideoAssetDirection.INPUT)
                .map(asset -> new VideoInputAssetResponse(
                        asset.getMediaType(), asset.getAssetRole(), displaySourceUrl(asset),
                        "/video/generations/" + task.getTaskCode() + "/inputs/" + asset.getId(),
                        asset.getSortOrder()))
                .toList();
        List<String> results = assets.stream()
                .filter(asset -> asset.getDirection() == VideoAssetDirection.OUTPUT)
                .map(asset -> asset.getLocalPath() != null ? "/video-media/" + asset.getLocalPath()
                        : asset.getProviderUrl())
                .filter(value -> value != null && !value.isBlank())
                .toList();
        boolean terminal = task.getStatus().isTerminal();
        String publicStatus = task.getStatus().name().equals("COMPLETED") ? "completed"
                : task.getStatus().name().equals("FAILED") ? "failed" : "processing";
        int cost = task.getBillingStatus().name().equals("SETTLED")
                ? task.getChargedCredits()
                : task.getReservedCredits();
        return new VideoGenerationResponse(
                task.getTaskCode(),
                task.getClientMessageId(),
                publicStatus,
                task.getStatus().name().toLowerCase(),
                !terminal,
                task.getModel(),
                task.getGenerationType().name().toLowerCase(),
                task.getPrompt(),
                task.getDurationSeconds(),
                task.getAspectRatio(),
                task.getResolution(),
                cost,
                task.getProviderCost(),
                inputAssets,
                results,
                publicStatus.equals("failed") ? task.getErrorCode() : null,
                publicStatus.equals("failed") ? task.getErrorMessage() : null,
                publicStatus.equals("failed") ? task.getErrorSuggestion() : null,
                task.getCreatedAt(),
                task.getCompletedAt());
    }

    private String displaySourceUrl(VideoGenerationAsset asset) {
        if (asset.getLocalPath() == null || asset.getLocalPath().isBlank()) {
            return asset.getSourceUrl();
        }
        String normalized = asset.getLocalPath().replace('\\', '/').replaceFirst("^/+", "");
        return "/video-media/" + normalized;
    }
}
