package com.example.chatimg2.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ImageApiClient {

    private final ImageBackupService imageBackupService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.api.key}")
    private String apiKey;

    @Value("${app.api.url}")
    private String apiUrl;

    @Value("${app.api.model.text2img:gpt-image-2}")
    private String defaultText2imgModel;

    @Value("${app.api.poll.initial-delay-ms:5000}")
    private long pollInitialDelayMs;

    @Value("${app.api.poll.interval-ms:5000}")
    private long pollIntervalMs;

    @Value("${app.api.poll.max-wait-ms:600000}")
    private long pollMaxWaitMs;

    @Value("${app.api.http.connect-timeout-ms:60000}")
    private long connectTimeoutMs;

    @Value("${app.api.http.submit-read-timeout-ms:600000}")
    private long submitReadTimeoutMs;

    @Value("${app.api.http.poll-read-timeout-ms:60000}")
    private long pollReadTimeoutMs;

    private static final Set<String> COMPLETED_STATUSES = Set.of(
            "completed", "success", "succeeded", "done", "finished"
    );
    private static final Set<String> FAILED_STATUSES = Set.of(
            "failed", "error", "cancelled", "canceled", "timeout"
    );

    private OkHttpClient submitClient;
    private OkHttpClient pollClient;

    public ImageApiClient(ImageBackupService imageBackupService) {
        this.imageBackupService = imageBackupService;
    }

    @jakarta.annotation.PostConstruct
    void initHttpClients() {
        this.submitClient = new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(submitReadTimeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .build();
        this.pollClient = new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(pollReadTimeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .build();
    }

    public String textToImage(String prompt, String requestedModel, List<String> fallbacks) {
        RuntimeException lastError = null;
        List<String> models = buildText2imgModels(requestedModel, fallbacks);
        for (int i = 0; i < models.size(); i++) {
            String model = models.get(i);
            try {
                var body = new java.util.HashMap<String, Object>();
                body.put("model", model);
                body.put("prompt", prompt);
                body.put("n", 1);
                body.put("size", "1024x1024");
                // 不使用 b64_json：会迫使 API 同步等待生图完成，容易触发超时
                if (model.startsWith("gpt-image")) {
                    body.put("quality", "medium");
                }
                String jsonBody = objectMapper.writeValueAsString(body);
                Request request = new Request.Builder()
                        .url(getApiBaseUrl() + "/v1/images/generations")
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .addHeader("Content-Type", "application/json")
                        .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                        .build();

                log.info("Text2img trying model: {}", model);
                return resolveImageFromSubmit(request);
            } catch (RuntimeException e) {
                lastError = e;
                boolean hasNext = i < models.size() - 1;
                if (isModelNotFound(e) && hasNext) {
                    log.warn("Model {} not available, trying next...", model);
                    continue;
                }
                if (isTimeoutError(e) && hasNext) {
                    log.warn("Model {} timed out, trying next...", model);
                    continue;
                }
                throw new RuntimeException("文生图请求失败: " + e.getMessage(), e);
            } catch (Exception e) {
                throw new RuntimeException("文生图请求失败: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException(
                "文生图失败：当前 API 密钥分组未开通生图模型。"
                        + "请到 maitokens 令牌管理重新创建密钥，选择含 gpt-image-2 的分组（如 sora/image）。"
                        + "最后错误: " + (lastError != null ? lastError.getMessage() : "unknown"));
    }

    public String imageEdit(String prompt, byte[] imageBytes, String model) {
        try {
            RequestBody fileBody = RequestBody.create(imageBytes, MediaType.parse("image/png"));
            MultipartBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("image", "source.png", fileBody)
                    .addFormDataPart("prompt", prompt)
                    .addFormDataPart("model", model)
                    .addFormDataPart("n", "1")
                    .addFormDataPart("size", "1024x1024")
                    .build();

            Request request = new Request.Builder()
                    .url(getApiBaseUrl() + "/v1/images/edits")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(body)
                    .build();

            log.info("Img2img using model: {}", model);
            return resolveImageFromSubmit(request);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("图生图请求失败: " + e.getMessage(), e);
        }
    }

    private List<String> buildText2imgModels(String requestedModel, List<String> fallbacks) {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        if (requestedModel != null && !requestedModel.isBlank()) {
            models.add(requestedModel.trim());
        }
        models.add(defaultText2imgModel);
        models.addAll(fallbacks);
        return new ArrayList<>(models);
    }

    private String resolveImageFromSubmit(Request request) throws IOException {
        try (Response response = submitClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("API call failed: {} - {}", response.code(), responseBody);
                throw new RuntimeException("API调用失败: " + response.code() + " - " + extractApiError(responseBody));
            }
            log.debug("API submit response: {}", truncate(responseBody, 500));
            return resolveImageFromJson(objectMapper.readTree(responseBody));
        } catch (java.net.SocketTimeoutException e) {
            throw new RuntimeException("API 连接超时（生图耗时较长），请稍后重试", e);
        }
    }

    private String resolveImageFromJson(JsonNode root) throws IOException {
        String syncImage = extractImageUrl(root);
        if (syncImage != null) {
            return syncImage;
        }

        String status = extractStatus(root);
        if (status != null && isCompletedStatus(status)) {
            throw new RuntimeException("任务已完成但未返回图片");
        }

        String taskId = extractTaskId(root);
        if (taskId != null) {
            log.info("Async task submitted, polling taskId={}", taskId);
            return pollTaskUntilComplete(taskId);
        }

        throw new RuntimeException("API返回数据中无图片或任务ID");
    }

    private String pollTaskUntilComplete(String taskId) throws IOException {
        String pollUrl = getApiBaseUrl() + "/v1/tasks/" + taskId;
        sleepQuietly(pollInitialDelayMs);

        long deadline = System.currentTimeMillis() + pollMaxWaitMs;
        int attempt = 0;

        while (System.currentTimeMillis() < deadline) {
            attempt++;
            Request pollRequest = new Request.Builder()
                    .url(pollUrl)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .get()
                    .build();

            try (Response response = pollClient.newCall(pollRequest).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.warn("Poll task {} attempt {} HTTP {}: {}", taskId, attempt, response.code(), body);
                    sleepQuietly(pollIntervalMs);
                    continue;
                }

                JsonNode root = objectMapper.readTree(body);
                String status = extractStatus(root);
                log.info("Poll task {} attempt {} status={}", taskId, attempt, status);

                if (status != null && isCompletedStatus(status)) {
                    String imageUrl = extractImageUrl(root);
                    if (imageUrl != null) {
                        return imageUrl;
                    }
                    throw new RuntimeException("生图任务完成但未返回图片");
                }
                if (status != null && isFailedStatus(status)) {
                    throw new RuntimeException("生图任务失败: " + extractTaskError(root));
                }
            }

            sleepQuietly(pollIntervalMs);
        }

        throw new RuntimeException("生图超时（超过 " + (pollMaxWaitMs / 1000) + " 秒），请稍后重试");
    }

    private String extractImageUrl(JsonNode root) throws IOException {
        String fromData = extractImageFromDataArray(root.get("data"));
        if (fromData != null) {
            return fromData;
        }

        JsonNode result = root.get("result");
        if (result != null) {
            String fromResultImages = extractImageFromDataArray(result.get("images"));
            if (fromResultImages != null) {
                return fromResultImages;
            }
            if (result.has("url")) {
                return result.get("url").asText();
            }
        }

        JsonNode output = root.get("output");
        if (output != null) {
            String fromOutput = extractImageFromDataArray(output.get("image_urls"));
            if (fromOutput != null) {
                return fromOutput;
            }
            if (output.has("url")) {
                return output.get("url").asText();
            }
        }

        return null;
    }

    private String extractImageFromDataArray(JsonNode array) throws IOException {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return null;
        }
        JsonNode first = array.get(0);
        if (first.isTextual()) {
            return first.asText();
        }
        if (first.has("url")) {
            return first.get("url").asText();
        }
        if (first.has("b64_json")) {
            return imageBackupService.saveBase64(first.get("b64_json").asText());
        }
        return null;
    }

    private String extractTaskId(JsonNode root) {
        for (String field : List.of("task_id", "taskId")) {
            if (root.has(field) && root.get(field).isTextual()) {
                return root.get(field).asText();
            }
        }

        if (root.has("id") && root.get("id").isTextual()) {
            String status = extractStatus(root);
            if (status == null || !isCompletedStatus(status) || extractImageUrlSafe(root) == null) {
                return root.get("id").asText();
            }
        }

        JsonNode data = root.get("data");
        if (data != null && data.isArray() && !data.isEmpty()) {
            JsonNode first = data.get(0);
            if (first.has("task_id")) {
                return first.get("task_id").asText();
            }
            if (first.has("id") && !first.has("url") && !first.has("b64_json")) {
                return first.get("id").asText();
            }
        }
        return null;
    }

    private String extractImageUrlSafe(JsonNode root) {
        try {
            return extractImageUrl(root);
        } catch (IOException e) {
            return null;
        }
    }

    private String extractStatus(JsonNode root) {
        for (String field : List.of("status", "state")) {
            if (root.has(field) && root.get(field).isTextual()) {
                return root.get(field).asText().toLowerCase();
            }
        }
        JsonNode task = root.get("task");
        if (task != null && task.has("status")) {
            return task.get("status").asText().toLowerCase();
        }
        JsonNode data = root.get("data");
        if (data != null && data.isObject() && data.has("status")) {
            return data.get("status").asText().toLowerCase();
        }
        return null;
    }

    private String extractTaskError(JsonNode root) {
        JsonNode error = root.get("error");
        if (error != null) {
            if (error.has("message")) {
                return error.get("message").asText();
            }
            if (error.isTextual()) {
                return error.asText();
            }
        }
        if (root.has("message")) {
            return root.get("message").asText();
        }
        return "unknown";
    }

    private boolean isCompletedStatus(String status) {
        return COMPLETED_STATUSES.contains(status.toLowerCase());
    }

    private boolean isFailedStatus(String status) {
        return FAILED_STATUSES.contains(status.toLowerCase());
    }

    private boolean isModelNotFound(RuntimeException e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("model_not_found") || msg.contains("No available channel"));
    }

    private boolean isTimeoutError(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof java.net.SocketTimeoutException) {
                return true;
            }
            String msg = current.getMessage();
            if (msg != null && msg.toLowerCase().contains("timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }

    private String extractApiError(String errorBody) {
        try {
            JsonNode root = objectMapper.readTree(errorBody);
            JsonNode err = root.get("error");
            if (err != null && err.has("message")) {
                return err.get("message").asText();
            }
        } catch (Exception ignored) {
        }
        return errorBody.length() > 200 ? errorBody.substring(0, 200) : errorBody;
    }

    private String getApiBaseUrl() {
        String baseUrl = apiUrl == null || apiUrl.isBlank()
                ? "https://www.maitokens.com"
                : apiUrl.trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (baseUrl.endsWith("/v1")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 3);
        }
        if (baseUrl.equals("https://www.maitokens.com")) {
            baseUrl = "https://wcf.maitokens.com";
        }
        return baseUrl;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("生图轮询被中断", e);
        }
    }
}
