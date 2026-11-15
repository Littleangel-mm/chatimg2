package com.example.chatimg2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageApiErrorClassifierTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void modelNotFoundIsNotRetryable() {
        var details = parse("""
                {"error":{"code":"model_not_found","type":"new_api_error",
                "message":"No available channel for model gpt-image-2"}}
                """);

        assertTrue(ImageApiErrorClassifier.isModelNotFound(details));
        assertFalse(ImageApiErrorClassifier.isRetryable(503, details));
    }

    @Test
    void upstreamFailuresAndRateLimitsAreRetryable() {
        var upstream = parse("""
                {"error":{"code":"upstream_error","type":"upstream_error",
                "message":"Upstream request failed"}}
                """);
        var rateLimit = parse("""
                {"error":{"code":"rate_limit_exceeded","type":"rate_limit_error",
                "message":"Too many requests"}}
                """);

        assertTrue(ImageApiErrorClassifier.isRetryable(502, upstream));
        assertTrue(ImageApiErrorClassifier.isRetryable(429, rateLimit));
    }

    @Test
    void contentPolicyErrorsAreNotRetryable() {
        var details = parse("""
                {"error":{"code":"content_policy_violation","type":"image_generation_user_error",
                "message":"请调整图片内容后重试"}}
                """);

        assertTrue(ImageApiErrorClassifier.isContentPolicyError(details));
        assertFalse(ImageApiErrorClassifier.isRetryable(500, details));
    }

    @Test
    void onlyExplicitSizeErrorsUseTheNextSizeOption() {
        var sizeError = parse("""
                {"error":{"code":"invalid_parameter","type":"invalid_request_error",
                "message":"Invalid size, must be one of the supported dimensions"}}
                """);
        var promptError = parse("""
                {"error":{"code":"invalid_parameter","type":"invalid_request_error",
                "message":"Invalid prompt"}}
                """);

        assertTrue(ImageApiErrorClassifier.isSizeError(400, sizeError));
        assertFalse(ImageApiErrorClassifier.isSizeError(400, promptError));
    }

    private ImageApiErrorClassifier.ApiErrorDetails parse(String body) {
        return ImageApiErrorClassifier.parse(objectMapper, body);
    }
}
