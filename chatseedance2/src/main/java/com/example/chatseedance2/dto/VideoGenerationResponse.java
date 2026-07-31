package com.example.chatseedance2.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record VideoGenerationResponse(
        String taskCode,
        String clientMessageId,
        String status,
        String detailStatus,
        boolean recoverable,
        String model,
        String generationType,
        String prompt,
        int durationSeconds,
        String aspectRatio,
        String resolution,
        int creditsCost,
        BigDecimal providerCost,
        List<String> results,
        String errorCode,
        String errorMessage,
        String errorSuggestion,
        LocalDateTime createdAt,
        LocalDateTime completedAt
) {
}
