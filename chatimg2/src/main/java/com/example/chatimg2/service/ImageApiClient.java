package com.example.chatimg2.service;

import com.example.chatimg2.exception.ApiCallException;
import com.example.chatimg2.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ImageApiClient {

    private final ImageBackupService imageBackupService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String AUTO_IMAGE_SIZE = "auto";
    private static final String DEFAULT_IMAGE_SIZE = "1024x1024";
    private static final String PORTRAIT_IMAGE_SIZE = "1024x1536";
    private static final String LANDSCAPE_IMAGE_SIZE = "1536x1024";

    @Value("${app.api.key}")
    private String apiKey;

    @Value("${app.api.url}")
    private String apiUrl;

    @Value("${app.api.model.text2img:gpt-image-2}")
    private String defaultText2imgModel;

    @Value("${app.api.image.size:auto}")
    private String configuredImageSize;

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

    @Value("${app.api.submit.max-attempts:3}")
    private int submitMaxAttempts;

    @Value("${app.api.submit.retry-delay-ms:3000}")
    private long submitRetryDelayMs;

    @Value("${app.api.compat.maitokens-www-to-wcf:true}")
    private boolean maitokensWwwToWcf;

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
        log.info("Image API base URL: configured={}, resolved={}", sanitizeBaseUrl(apiUrl), getApiBaseUrl());
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
        ApiCallException lastError = null;
        List<String> models = buildText2imgModels(requestedModel, fallbacks);
        if (models.isEmpty()) {
            throw new BusinessException("IMAGE_MODEL_MISSING", "未配置可用的文生图模型");
        }
        for (int i = 0; i < models.size(); i++) {
            String model = models.get(i);
            List<String> sizes = buildSizeAttempts(resolveImageSizeFromPrompt(prompt));
            for (int sizeIndex = 0; sizeIndex < sizes.size(); sizeIndex++) {
                String size = sizes.get(sizeIndex);
                try {
                    var body = new java.util.HashMap<String, Object>();
                    body.put("model", model);
                    body.put("prompt", prompt);
                    body.put("n", 1);
                    if (size != null && !size.isBlank()) {
                        body.put("size", size);
                    }
                    String jsonBody = objectMapper.writeValueAsString(body);
                    Request request = new Request.Builder()
                            .url(getApiBaseUrl() + "/v1/images/generations")
                            .addHeader("Authorization", "Bearer " + apiKey)
                            .addHeader("Content-Type", "application/json")
                            .addHeader("Idempotency-Key", UUID.randomUUID().toString())
                            .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                            .build();

                    log.info("Text2img trying model: {}, size={}", model, size == null ? "omitted" : size);
                    return resolveImageFromSubmit(request);
                } catch (ApiCallException e) {
                    lastError = e;
                    boolean hasNextSize = sizeIndex < sizes.size() - 1;
                    if (hasNextSize && shouldRetryText2imgWithNextSize(e)) {
                        log.warn("Text2img size {} failed, trying next size option: {}",
                                size == null ? "omitted" : size, e.getMessage());
                        continue;
                    }
                    boolean hasNext = i < models.size() - 1;
                    if (isModelNotFound(e) && hasNext) {
                        log.warn("Model {} not available, trying next...", model);
                        break;
                    }
                    throw e;
                } catch (JsonProcessingException e) {
                    throw new ApiCallException(null, "request_serialization_error", "invalid_request_body",
                            false, "文生图请求序列化失败", "生图请求处理失败，请稍后重试", e);
                }
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new BusinessException("IMAGE_MODEL_UNAVAILABLE", "当前没有可用的文生图模型");
    }

    public String imageEdit(String prompt, byte[] imageBytes, String model) {
        return imageEdit(prompt, imageBytes, "image/png", "source.png", model, List.of());
    }

    public String imageEdit(String prompt, byte[] imageBytes, String requestedModel, List<String> fallbacks) {
        return imageEdit(prompt, imageBytes, "image/png", "source.png", requestedModel, fallbacks);
    }

    public String imageEdit(String prompt, byte[] imageBytes, String imageMediaType, String fileName,
                            String requestedModel, List<String> fallbacks) {
        ApiCallException lastError = null;
        List<String> models = buildImageEditModels(requestedModel, fallbacks);
        if (models.isEmpty()) {
            throw new BusinessException("IMAGE_MODEL_MISSING", "未配置可用的图生图模型");
        }
        for (int i = 0; i < models.size(); i++) {
            String model = models.get(i);
            try {
                return submitImageEdit(prompt, imageBytes, imageMediaType, fileName, model);
            } catch (ApiCallException e) {
                lastError = e;
                boolean hasNext = i < models.size() - 1;
                if (hasNext && isModelNotFound(e)) {
                    log.warn("Img2img model {} is unavailable, trying next model", model);
                    continue;
                }
                throw e;
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new BusinessException("IMAGE_MODEL_UNAVAILABLE", "当前没有可用的图生图模型");
    }

    private String submitImageEdit(String prompt, byte[] imageBytes, String imageMediaType,
                                   String fileName, String model) {
        String mediaType = imageMediaType == null || imageMediaType.isBlank() ? "image/png" : imageMediaType;
        String uploadName = fileName == null || fileName.isBlank() ? "source.png" : fileName;
        String size = resolveImageSizeFromPrompt(prompt);
        RequestBody fileBody = RequestBody.create(imageBytes, MediaType.parse(mediaType));
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", uploadName, fileBody)
                .addFormDataPart("prompt", prompt)
                .addFormDataPart("model", model)
                .addFormDataPart("n", "1")
                .addFormDataPart("size", size)
                .build();

        Request request = new Request.Builder()
                .url(getApiBaseUrl() + "/v1/images/edits")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Idempotency-Key", UUID.randomUUID().toString())
                .post(body)
                .build();

        log.info("Img2img using model: {}, mediaType={}, size={}", model, mediaType, size);
        return resolveImageFromSubmit(request);
    }

    private List<String> buildText2imgModels(String requestedModel, List<String> fallbacks) {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        if (isGptImageModel(requestedModel)) {
            models.add(requestedModel.trim());
        }
        if (isGptImageModel(defaultText2imgModel)) {
            models.add(defaultText2imgModel.trim());
        }
        if (fallbacks != null) {
            fallbacks.stream()
                    .filter(this::isGptImageModel)
                    .map(String::trim)
                    .forEach(models::add);
        }
        return new ArrayList<>(models);
    }

    private List<String> buildImageEditModels(String requestedModel, List<String> fallbacks) {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        if (isGptImageModel(requestedModel)) {
            models.add(requestedModel.trim());
        }
        if (fallbacks != null) {
            fallbacks.stream()
                    .filter(this::isGptImageModel)
                    .map(String::trim)
                    .forEach(models::add);
        }
        return new ArrayList<>(models);
    }

    private boolean isGptImageModel(String model) {
        return model != null && model.trim().startsWith("gpt-image");
    }

    private String resolveImageSizeFromPrompt(String prompt) {
        String text = normalizePrompt(prompt);
        if (containsAny(text, "9:16", "9/16", "916", "1080x1920", "720x1280", "竖屏", "竖版", "竖图", "手机壁纸", "portrait", "vertical")) {
            return PORTRAIT_IMAGE_SIZE;
        }
        if (containsAny(text, "16:9", "16/9", "169", "1920x1080", "1280x720", "横屏", "横版", "横图", "landscape", "horizontal", "wide")) {
            return LANDSCAPE_IMAGE_SIZE;
        }
        if (containsAny(text, "1:1", "1/1", "方图", "方形", "square")) {
            return DEFAULT_IMAGE_SIZE;
        }
        return DEFAULT_IMAGE_SIZE;
    }

    private List<String> buildSizeAttempts(String preferredSize) {
        LinkedHashSet<String> sizes = new LinkedHashSet<>();
        if (preferredSize != null && !preferredSize.isBlank()) {
            sizes.add(preferredSize.trim());
        }
        if (configuredImageSize != null && !configuredImageSize.isBlank()) {
            sizes.add(configuredImageSize.trim());
        }
        sizes.add(AUTO_IMAGE_SIZE);
        sizes.add(null);
        sizes.add(DEFAULT_IMAGE_SIZE);
        return new ArrayList<>(sizes);
    }

    private boolean shouldRetryText2imgWithNextSize(ApiCallException error) {
        Integer status = error.getHttpStatus();
        if (status == null) {
            return false;
        }
        return ImageApiErrorClassifier.isSizeError(status, toErrorDetails(error));
    }

    private String normalizePrompt(String prompt) {
        return (prompt == null ? "" : prompt)
                .toLowerCase(Locale.ROOT)
                .replace('\uff1a', ':')
                .replace('\u00d7', 'x')
                .replace('*', 'x');
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private String resolveImageFromSubmit(Request request) {
        int attempts = Math.max(1, submitMaxAttempts);
        ApiCallException lastError = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try (Response response = submitClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    ApiCallException error = createResponseException(response.code(), responseBody);
                    lastError = error;
                    if (error.isRetryable() && attempt < attempts) {
                        log.warn("API submit failed transiently, retry {}/{} after HTTP {}: {}",
                                attempt, attempts, response.code(), truncate(responseBody, 300));
                        sleepBeforeSubmitRetry();
                        continue;
                    }
                    log.error("API call failed: {} - {}", response.code(), responseBody);
                    throw error;
                }
                log.debug("API submit response: {}", truncate(responseBody, 500));
                JsonNode root;
                try {
                    root = objectMapper.readTree(responseBody);
                } catch (JsonProcessingException e) {
                    ApiCallException error = new ApiCallException(response.code(), "bad_response_body",
                            "bad_response_body", true,
                            "生图 API 返回了无法解析的数据: " + truncate(responseBody, 300),
                            "生图服务返回异常，请稍后重试", e);
                    lastError = error;
                    if (attempt < attempts) {
                        log.warn("API returned invalid JSON, retry {}/{}: {}",
                                attempt, attempts, truncate(responseBody, 300));
                        sleepBeforeSubmitRetry();
                        continue;
                    }
                    throw error;
                }
                return resolveImageFromJson(root);
            } catch (java.net.SocketTimeoutException e) {
                ApiCallException error = createTransportException("生图 API 请求超时", e);
                lastError = error;
                if (attempt < attempts) {
                    log.warn("API submit timed out, retry {}/{}", attempt, attempts);
                    sleepBeforeSubmitRetry();
                    continue;
                }
                throw error;
            } catch (IOException e) {
                ApiCallException error = createTransportException("生图 API 网络请求失败: " + e.getMessage(), e);
                lastError = error;
                if (attempt < attempts) {
                    log.warn("API submit network error, retry {}/{}: {}", attempt, attempts, e.getMessage());
                    sleepBeforeSubmitRetry();
                    continue;
                }
                throw error;
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new ApiCallException(null, "unknown", "unknown", false,
                "生图 API 请求未执行", "生图请求处理失败，请稍后重试");
    }

    private void sleepBeforeSubmitRetry() {
        sleepQuietly(Math.max(0, submitRetryDelayMs));
    }

    private String resolveImageFromJson(JsonNode root) {
        String syncImage = extractImageUrl(root);
        if (syncImage != null) {
            return syncImage;
        }

        String status = extractStatus(root);
        if (status != null && isCompletedStatus(status)) {
            throw new ApiCallException(200, "bad_response_body", "missing_image", false,
                    "生图任务已完成但未返回图片", "生图服务未返回图片，请稍后重试");
        }

        String taskId = extractTaskId(root);
        if (taskId != null) {
            log.info("Async task submitted, polling taskId={}", taskId);
            return pollTaskUntilComplete(taskId);
        }

        throw new ApiCallException(200, "bad_response_body", "missing_result", false,
                "生图 API 返回数据中没有图片或任务 ID: " + truncate(root.toString(), 300),
                "生图服务返回异常，请稍后重试");
    }

    private String pollTaskUntilComplete(String taskId) {
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
                    ApiCallException error = createResponseException(response.code(), body);
                    if (!error.isRetryable()) {
                        throw error;
                    }
                    log.warn("Poll task {} attempt {} transient HTTP {}: {}",
                            taskId, attempt, response.code(), truncate(body, 300));
                    sleepQuietly(pollIntervalMs);
                    continue;
                }

                JsonNode root;
                try {
                    root = objectMapper.readTree(body);
                } catch (JsonProcessingException e) {
                    log.warn("Poll task {} attempt {} returned invalid JSON: {}",
                            taskId, attempt, truncate(body, 300));
                    sleepQuietly(pollIntervalMs);
                    continue;
                }
                String status = extractStatus(root);
                log.info("Poll task {} attempt {} status={}", taskId, attempt, status);

                if (status != null && isCompletedStatus(status)) {
                    String imageUrl = extractImageUrl(root);
                    if (imageUrl != null) {
                        return imageUrl;
                    }
                    throw new ApiCallException(200, "bad_response_body", "missing_image", false,
                            "生图任务完成但未返回图片，taskId=" + taskId,
                            "生图服务未返回图片，请稍后重试");
                }
                if (status != null && isFailedStatus(status)) {
                    ImageApiErrorClassifier.ApiErrorDetails details =
                            ImageApiErrorClassifier.parse(objectMapper, root.toString());
                    throw new ApiCallException(200, details.type(), details.code(), false,
                            "生图任务失败，taskId=" + taskId + ": " + details.message(),
                            ImageApiErrorClassifier.isContentPolicyError(details)
                                    ? details.message()
                                    : "生成失败，请调整提示词或图片后重试");
                }
            } catch (java.net.SocketTimeoutException e) {
                log.warn("Poll task {} attempt {} timed out", taskId, attempt);
            } catch (IOException e) {
                log.warn("Poll task {} attempt {} network error: {}", taskId, attempt, e.getMessage());
            }

            sleepQuietly(pollIntervalMs);
        }

        throw new ApiCallException(null, "timeout", "poll_timeout", true,
                "生图轮询超时，taskId=" + taskId + "，maxWaitMs=" + pollMaxWaitMs,
                "生图等待超时，请稍后重试");
    }

    private String extractImageUrl(JsonNode root) {
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

    private String extractImageFromDataArray(JsonNode array) {
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
        return extractImageUrl(root);
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

    private boolean isCompletedStatus(String status) {
        return COMPLETED_STATUSES.contains(status.toLowerCase());
    }

    private boolean isFailedStatus(String status) {
        return FAILED_STATUSES.contains(status.toLowerCase());
    }

    private boolean isModelNotFound(ApiCallException error) {
        return ImageApiErrorClassifier.isModelNotFound(toErrorDetails(error));
    }

    private ImageApiErrorClassifier.ApiErrorDetails toErrorDetails(ApiCallException error) {
        return new ImageApiErrorClassifier.ApiErrorDetails(
                error.getMessage(), error.getApiErrorType(), error.getApiErrorCode());
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }

    private ApiCallException createResponseException(int httpStatus, String responseBody) {
        ImageApiErrorClassifier.ApiErrorDetails details =
                ImageApiErrorClassifier.parse(objectMapper, responseBody);
        boolean retryable = ImageApiErrorClassifier.isRetryable(httpStatus, details);
        String internalMessage = "生图 API 调用失败: HTTP " + httpStatus
                + ", type=" + blankAsUnknown(details.type())
                + ", code=" + blankAsUnknown(details.code())
                + ", message=" + details.message();
        return new ApiCallException(
                httpStatus,
                details.type(),
                details.code(),
                retryable,
                internalMessage,
                ImageApiErrorClassifier.userMessage(httpStatus, details)
        );
    }

    private ApiCallException createTransportException(String internalMessage, IOException cause) {
        return new ApiCallException(null, "network_error", "network_error", true,
                internalMessage, "生图服务连接失败，请稍后重试", cause);
    }

    private String blankAsUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
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
        if (maitokensWwwToWcf && baseUrl.equals("https://www.maitokens.com")) {
            return "https://wcf.maitokens.com";
        }
        return baseUrl;
    }

    private String sanitizeBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            return "https://www.maitokens.com";
        }
        return value.trim();
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiCallException(null, "interrupted", "request_interrupted", false,
                    "生图任务等待被中断", "生图任务已中断，请重新提交", e);
        }
    }
}
