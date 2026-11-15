package com.example.chatseedance2.dto;

import com.example.chatseedance2.entity.VideoTaskEvent;

import java.time.LocalDateTime;
import java.util.Map;

public record VideoTaskEventResponse(
        Long id,
        String eventType,
        String fromStatus,
        String toStatus,
        Integer providerHttpStatus,
        String providerErrorCode,
        Boolean retryable,
        String message,
        Map<String, Object> payload,
        LocalDateTime createdAt
) {
    public static VideoTaskEventResponse from(VideoTaskEvent event) {
        return new VideoTaskEventResponse(
                event.getId(),
                event.getEventType().name().toLowerCase(),
                event.getFromStatus() == null ? null : event.getFromStatus().name().toLowerCase(),
                event.getToStatus() == null ? null : event.getToStatus().name().toLowerCase(),
                event.getProviderHttpStatus(),
                event.getProviderErrorCode(),
                event.getRetryable(),
                event.getMessage(),
                event.getPayload(),
                event.getCreatedAt());
    }
}
