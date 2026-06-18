package com.example.chatimg2.service;

import com.example.chatimg2.entity.ActivationKey;
import com.example.chatimg2.entity.GenerationRecord;
import com.example.chatimg2.repository.GenerationRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageGenerationService {

    private final KeyService keyService;
    private final GenerationRecordRepository recordRepository;
    private final ImageBackupService imageBackupService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.api.key}")
    private String apiKey;

    @Value("${app.api.url}")
    private String apiUrl;

    @Value("${app.image.backup-path}")
    private String backupPath;

    @Value("${app.api.model.text2img:gpt-image-2}")
    private String defaultText2imgModel;

    @Value("${app.api.model.img2img:gpt-image-2}")
    private String defaultImg2imgModel;

    private static final int CREDITS_PER_GENERATION = 20;

    private static final List<String> TEXT2IMG_FALLBACKS = List.of(
            "gpt-image-2", "gpt-image-1", "dall-e-2", "dall-e-3"
    );

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    @Transactional
    public GenerationRecord generateImage(String keyCode, String prompt, String type,
                                          String sourceImageBase64, String model) {
        ActivationKey key = keyService.activate(keyCode)
                .orElseThrow(() -> new RuntimeException("密钥无效或已禁用"));

        if (key.getRemainingCredits() < CREDITS_PER_GENERATION) {
            throw new RuntimeException("积分不足，剩余: " + key.getRemainingCredits());
        }

        String generationType = "img2img".equals(type) ? "img2img" : "text2img";
        String sourceImagePath = null;

        String imageUrl;
        if ("img2img".equals(generationType)) {
            if (sourceImageBase64 == null || sourceImageBase64.isBlank()) {
                throw new RuntimeException("图生图需要上传参考图片");
            }
            byte[] imageBytes = decodeBase64Image(sourceImageBase64);
            sourceImagePath = saveSourceImage(imageBytes);
            String img2imgModel = (model != null && !model.isBlank()) ? model : defaultImg2imgModel;
            imageUrl = callImageEditApi(prompt, imageBytes, img2imgModel);
        } else {
            imageUrl = callTextToImageApi(prompt, model);
        }

        if (!keyService.deductCredits(key.getId(), CREDITS_PER_GENERATION)) {
            throw new RuntimeException("积分扣除失败");
        }

        GenerationRecord record = new GenerationRecord();
        record.setKeyId(key.getId());
        record.setPrompt(prompt);
        record.setImageUrl(imageUrl);
        record.setGenerationType(generationType);
        record.setSourceImagePath(sourceImagePath);
        record.setCreditsCost(CREDITS_PER_GENERATION);

        if (imageUrl.startsWith("/api/images/")) {
            record.setLocalPath(imageUrl.replace("/api/images/", "./img/"));
        }

        record = recordRepository.save(record);

        if (record.getLocalPath() == null) {
            imageBackupService.backupAsync(record.getId(), imageUrl);
        }

        return record;
    }

    public List<GenerationRecord> getHistory(String keyCode) {
        ActivationKey key = keyService.getByKeyCode(keyCode)
                .orElseThrow(() -> new RuntimeException("密钥不存在"));
        return recordRepository.findTop30ByKeyIdOrderByCreatedAtDesc(key.getId());
    }

    private byte[] decodeBase64Image(String base64) {
        String data = base64;
        if (base64.contains(",")) {
            data = base64.substring(base64.indexOf(",") + 1);
        }
        return Base64.getDecoder().decode(data);
    }

    private String saveSourceImage(byte[] imageBytes) {
        try {
            Path dir = Paths.get(backupPath, "sources");
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            String fileName = "src_" + UUID.randomUUID() + ".png";
            Path filePath = dir.resolve(fileName);
            Files.write(filePath, imageBytes);
            return filePath.toString();
        } catch (IOException e) {
            throw new RuntimeException("保存参考图失败", e);
        }
    }

    private List<String> buildText2imgModels(String requestedModel) {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        if (requestedModel != null && !requestedModel.isBlank()) {
            models.add(requestedModel.trim());
        }
        models.add(defaultText2imgModel);
        models.addAll(TEXT2IMG_FALLBACKS);
        return new ArrayList<>(models);
    }

    private String callTextToImageApi(String prompt, String requestedModel) {
        String url = apiUrl + "/v1/images/generations";
        RuntimeException lastError = null;

        for (String model : buildText2imgModels(requestedModel)) {
            try {
                var body = new java.util.HashMap<String, Object>();
                body.put("model", model);
                body.put("prompt", prompt);
                body.put("n", 1);
                body.put("size", "1024x1024");
                if (model.startsWith("gpt-image")) {
                    body.put("response_format", "b64_json");
                    body.put("quality", "medium");
                }
                String jsonBody = objectMapper.writeValueAsString(body);

                Request request = new Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .addHeader("Content-Type", "application/json")
                        .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                        .build();

                log.info("Text2img trying model: {}", model);
                return parseImageUrlFromResponse(request);
            } catch (RuntimeException e) {
                lastError = e;
                if (isModelNotFound(e)) {
                    log.warn("Model {} not available, trying next...", model);
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

    private String callImageEditApi(String prompt, byte[] imageBytes, String model) {
        String url = apiUrl + "/v1/images/edits";
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
                    .url(url)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(body)
                    .build();

            log.info("Img2img using model: {}", model);
            return parseImageUrlFromResponse(request);
        } catch (Exception e) {
            throw new RuntimeException("图生图请求失败: " + e.getMessage(), e);
        }
    }

    private boolean isModelNotFound(RuntimeException e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("model_not_found")
                || msg.contains("No available channel"));
    }

    private String parseImageUrlFromResponse(Request request) throws IOException {
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "unknown error";
                log.error("API call failed: {} - {}", response.code(), errorBody);
                String shortMsg = extractApiError(errorBody);
                throw new RuntimeException("API调用失败: " + response.code() + " - " + shortMsg);
            }

            String responseBody = response.body().string();
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode dataArray = root.get("data");

            if (dataArray != null && dataArray.isArray() && !dataArray.isEmpty()) {
                JsonNode firstImage = dataArray.get(0);
                if (firstImage.has("url")) {
                    return firstImage.get("url").asText();
                }
                if (firstImage.has("b64_json")) {
                    return saveBase64Result(firstImage.get("b64_json").asText());
                }
            }
            throw new RuntimeException("API返回数据中无图片");
        }
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

    private String saveBase64Result(String b64) throws IOException {
        Path dir = Paths.get(backupPath);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        String fileName = UUID.randomUUID() + ".png";
        Path filePath = dir.resolve(fileName);
        Files.write(filePath, Base64.getDecoder().decode(b64));
        return "/api/images/" + fileName;
    }
}
