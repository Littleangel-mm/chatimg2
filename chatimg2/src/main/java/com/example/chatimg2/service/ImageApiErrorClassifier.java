package com.example.chatimg2.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Set;

final class ImageApiErrorClassifier {

    private static final Set<Integer> TRANSIENT_HTTP_STATUSES = Set.of(
            408, 425, 429, 500, 502, 503, 504, 522, 523, 524
    );
    private static final Set<String> NON_RETRYABLE_CODES = Set.of(
            "model_not_found",
            "content_policy_violation",
            "invalid_api_key",
            "insufficient_quota",
            "permission_denied",
            "account_deactivated",
            "invalid_request_error",
            "invalid_parameter",
            "unsupported_model"
    );
    private static final Set<String> NON_RETRYABLE_TYPES = Set.of(
            "image_generation_user_error",
            "authentication_error",
            "permission_error",
            "invalid_request_error"
    );

    private ImageApiErrorClassifier() {
    }

    static ApiErrorDetails parse(ObjectMapper objectMapper, String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return new ApiErrorDetails("上游服务返回空响应", "", "");
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode error = root.get("error");
            if (error != null && error.isObject()) {
                return new ApiErrorDetails(
                        text(error, "message", responseBody),
                        text(error, "type", ""),
                        text(error, "code", "")
                );
            }
            return new ApiErrorDetails(text(root, "message", responseBody), "", "");
        } catch (Exception ignored) {
            return new ApiErrorDetails(truncate(responseBody, 300), "", "bad_response_body");
        }
    }

    static boolean isRetryable(int httpStatus, ApiErrorDetails details) {
        if (isPermanent(details)) {
            return false;
        }
        return TRANSIENT_HTTP_STATUSES.contains(httpStatus);
    }

    static boolean isModelNotFound(ApiErrorDetails details) {
        return "model_not_found".equals(normalize(details.code()))
                || combined(details).contains("no available channel");
    }

    static boolean isContentPolicyError(ApiErrorDetails details) {
        return "content_policy_violation".equals(normalize(details.code()))
                || "image_generation_user_error".equals(normalize(details.type()));
    }

    static boolean isSizeError(int httpStatus, ApiErrorDetails details) {
        if (httpStatus != 400) {
            return false;
        }
        String combined = combined(details);
        boolean mentionsSize = combined.contains("size")
                || combined.contains("resolution")
                || combined.contains("dimension");
        boolean rejected = combined.contains("invalid")
                || combined.contains("unsupported")
                || combined.contains("must be");
        return mentionsSize && rejected;
    }

    static String userMessage(int httpStatus, ApiErrorDetails details) {
        if (isContentPolicyError(details)) {
            return details.message();
        }
        if (isModelNotFound(details)) {
            return "当前生图模型暂不可用，请联系管理员";
        }
        if (httpStatus == 401 || httpStatus == 403) {
            return "生图服务认证失败，请联系管理员";
        }
        if (httpStatus == 429) {
            return "生图请求较多，请稍后重试";
        }
        if (httpStatus >= 400 && httpStatus < 500) {
            return "生图请求未通过，请调整提示词或图片后重试";
        }
        return "生图服务暂时繁忙，请稍后重试";
    }

    private static boolean isPermanent(ApiErrorDetails details) {
        String code = normalize(details.code());
        String type = normalize(details.type());
        if (NON_RETRYABLE_CODES.contains(code) || NON_RETRYABLE_TYPES.contains(type)) {
            return true;
        }
        String combined = combined(details);
        return combined.contains("no available channel")
                || combined.contains("content policy")
                || combined.contains("insufficient quota")
                || combined.contains("invalid api key");
    }

    private static String combined(ApiErrorDetails details) {
        return (normalize(details.message()) + " " + normalize(details.type()) + " " + normalize(details.code())).trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        String text = value.asText("").trim();
        return text.isEmpty() ? fallback : text;
    }

    private static String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }

    record ApiErrorDetails(String message, String type, String code) {
    }
}
