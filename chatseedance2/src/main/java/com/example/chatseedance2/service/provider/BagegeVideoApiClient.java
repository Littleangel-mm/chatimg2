package com.example.chatseedance2.service.provider;

import com.example.chatseedance2.config.SeedanceApiProperties;
import com.example.chatseedance2.exception.ProviderApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class BagegeVideoApiClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Set<String> DEFINITIVE_ERROR_CODES = Set.of(
            "invalid_request", "invalid_api_key", "model_not_found", "insufficient_balance",
            "validation_error", "unsupported_parameter", "content_policy_violation");

    private final OkHttpClient seedanceHttpClient;
    private final ObjectMapper objectMapper;
    private final SeedanceApiProperties properties;

    public List<ProviderModel> listModels() {
        Request request = requestBuilder("/models").get().build();
        JsonNode root = execute(request, false);
        List<ProviderModel> models = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            models.add(new ProviderModel(
                    text(item, "id"),
                    text(item, "name"),
                    text(item, "category"),
                    strings(item.path("flow_types")),
                    strings(item.path("input_keys")),
                    decimal(item.get("cost"))));
        }
        return models;
    }

    public ProviderTaskSnapshot submit(Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            Request request = requestBuilder("/videos/generations")
                    .post(RequestBody.create(json, JSON))
                    .build();
            return snapshot(execute(request, true));
        } catch (ProviderApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderApiException("Serialize video request failed", false, false, e);
        }
    }

    public ProviderTaskSnapshot getTask(String providerTaskId) {
        Request request = requestBuilder("/tasks/" + providerTaskId).get().build();
        return snapshot(execute(request, false));
    }

    private JsonNode execute(Request request, boolean submission) {
        requireKey();
        try (Response response = seedanceHttpClient.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            JsonNode root = body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
            if (!response.isSuccessful()) {
                throw providerError(response.code(), root, submission);
            }
            return root;
        } catch (ProviderApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ProviderApiException("Provider request failed: " + e.getMessage(), true, submission, e);
        }
    }

    private Request.Builder requestBuilder(String path) {
        String baseUrl = properties.url() == null ? "" : properties.url().replaceFirst("/+$", "");
        return new Request.Builder()
                .url(baseUrl + path)
                .header("Authorization", "Bearer " + properties.key())
                .header("Accept", "application/json");
    }

    private ProviderApiException providerError(int status, JsonNode root, boolean submission) {
        JsonNode error = root.path("error");
        String code = text(error, "code");
        String message = text(error, "message");
        if (message == null) {
            message = "Provider returned HTTP " + status;
        }
        boolean retryable = status == 408 || status == 409 || status == 429 || status >= 500;
        boolean definitive = status < 500 || (code != null && DEFINITIVE_ERROR_CODES.contains(code));
        return new ProviderApiException(message, status, code, retryable, submission && !definitive);
    }

    private ProviderTaskSnapshot snapshot(JsonNode root) {
        JsonNode error = root.path("error");
        ProviderTaskSnapshot.ProviderError providerError = error.isObject()
                ? new ProviderTaskSnapshot.ProviderError(
                        text(error, "code"), text(error, "message"),
                        text(error, "reason"), text(error, "suggestion"))
                : null;
        return new ProviderTaskSnapshot(
                text(root, "id"),
                normalizeStatus(text(root, "status")),
                text(root, "model"),
                text(root, "type"),
                decimal(root.get("cost")),
                resultUrls(root),
                providerError,
                objectMapper.convertValue(root, new TypeReference<>() {}));
    }

    private List<String> resultUrls(JsonNode root) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        collectUrls(root.path("results"), urls);
        collectUrls(root.path("archived_results"), urls);
        collectUrls(root.path("data"), urls);
        JsonNode result = root.get("result");
        if (result != null) {
            collectUrls(result, urls);
        }
        return List.copyOf(urls);
    }

    private void collectUrls(JsonNode node, Set<String> urls) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual() && isHttpUrl(node.asText())) {
            urls.add(node.asText());
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> collectUrls(item, urls));
            return;
        }
        if (node.isObject()) {
            JsonNode url = node.get("url");
            if (url != null && url.isTextual() && isHttpUrl(url.asText())) {
                urls.add(url.asText());
            }
        }
    }

    private boolean isHttpUrl(String value) {
        return value != null && (value.startsWith("https://") || value.startsWith("http://"));
    }

    private List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(value -> {
                if (value.isTextual()) {
                    values.add(value.asText());
                }
            });
        }
        return values;
    }

    private BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return node.isNumber() ? node.decimalValue() : new BigDecimal(node.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isValueNode() ? null : value.asText();
    }

    private String normalizeStatus(String status) {
        return status == null ? null : status.trim().toLowerCase();
    }

    private void requireKey() {
        if (properties.key() == null || properties.key().isBlank()) {
            throw new ProviderApiException("SEEDANCE_API_KEY is not configured", 0,
                    "missing_api_key", false, false);
        }
    }
}
