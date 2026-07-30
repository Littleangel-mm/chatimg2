package com.example.chatseedance2.service;

import com.example.chatseedance2.dto.VideoGenerationResponse;
import com.example.chatseedance2.entity.VideoAssetDirection;
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
        List<String> results = assetRepository.findByTaskIdOrderBySortOrderAsc(task.getId()).stream()
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
                results,
                publicStatus.equals("failed") ? task.getErrorCode() : null,
                publicStatus.equals("failed") ? task.getErrorMessage() : null,
                publicStatus.equals("failed") ? task.getErrorSuggestion() : null,
                task.getCreatedAt(),
                task.getCompletedAt());
    }
}
