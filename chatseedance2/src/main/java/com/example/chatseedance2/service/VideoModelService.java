package com.example.chatseedance2.service;

import com.example.chatseedance2.config.SeedanceApiProperties;
import com.example.chatseedance2.dto.VideoModelResponse;
import com.example.chatseedance2.exception.BusinessException;
import com.example.chatseedance2.service.provider.BagegeVideoApiClient;
import com.example.chatseedance2.service.provider.ProviderModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.List;

@Service
@RequiredArgsConstructor
public class VideoModelService {

    public static final String SUPPORTED_MODEL_ID = "ty-me-sd20-933";
    public static final String SUPPORTED_MODEL_NAME = "官渠-Seedance 2.0 933 稳定满血版";
    private static final Map<String, String> LEGACY_MODEL_ALIASES = Map.of(
            "69cf3df6f9e33413dbd1db17", SUPPORTED_MODEL_ID,
            "ty-p-sd2-fast-720", SUPPORTED_MODEL_ID,
            "ty-p-sd2-720", SUPPORTED_MODEL_ID,
            "ty-sd2-mini-15s", SUPPORTED_MODEL_ID,
            "ty-sd2-fast-10s", SUPPORTED_MODEL_ID,
            "ty-sd20-930-720", SUPPORTED_MODEL_ID,
            "ty-q36-sd2-720", SUPPORTED_MODEL_ID);
    public static final BigDecimal USER_CREDITS_PER_SECOND = BigDecimal.valueOf(100);

    private final BagegeVideoApiClient apiClient;
    private final SeedanceApiProperties properties;

    private volatile ModelCache cache;

    public List<VideoModelResponse> listVideoModels() {
        return models().stream()
                .filter(model -> SUPPORTED_MODEL_ID.equals(model.id()))
                .filter(ProviderModel::supportsVideo)
                .map(model -> new VideoModelResponse(
                        model.id(), SUPPORTED_MODEL_NAME, model.category(),
                        model.flowTypes(), model.inputKeys(), USER_CREDITS_PER_SECOND))
                .toList();
    }

    public Quote quote(String requestedModel, int durationSeconds) {
        String modelId = canonicalModelId(requestedModel);
        if (!SUPPORTED_MODEL_ID.equals(modelId)) {
            throw new BusinessException("MODEL_NOT_ALLOWED", "当前仅支持官渠 Seedance 2.0 Fast 多参考模型");
        }
        ProviderModel model = models().stream()
                .filter(item -> modelId.equals(item.id()))
                .findFirst()
                .orElseThrow(() -> new BusinessException("MODEL_UNAVAILABLE", "当前模型不可用，请刷新模型列表"));
        if (!model.supportsVideo()) {
            throw new BusinessException("MODEL_NOT_VIDEO", "所选模型不支持视频生成");
        }
        int credits = Math.multiplyExact(USER_CREDITS_PER_SECOND.intValueExact(), durationSeconds);
        return new Quote(model, credits);
    }

    /** Keeps older deployed clients working while always calling the provider with the current model ID. */
    public static String canonicalModelId(String requestedModel) {
        String modelId = requestedModel == null || requestedModel.isBlank()
                ? SUPPORTED_MODEL_ID
                : requestedModel.trim();
        return LEGACY_MODEL_ALIASES.getOrDefault(modelId, modelId);
    }

    private List<ProviderModel> models() {
        ModelCache current = cache;
        Instant now = Instant.now();
        if (current != null && current.expiresAt().isAfter(now)) {
            return current.models();
        }
        synchronized (this) {
            current = cache;
            if (current != null && current.expiresAt().isAfter(now)) {
                return current.models();
            }
            try {
                List<ProviderModel> loaded = List.copyOf(apiClient.listModels());
                cache = new ModelCache(loaded,
                        now.plusMillis(Math.max(10_000L, properties.modelCacheMs())));
                return loaded;
            } catch (RuntimeException error) {
                if (current != null && !current.models().isEmpty()) {
                    return current.models();
                }
                throw error;
            }
        }
    }

    public record Quote(ProviderModel model, int credits) {
    }

    private record ModelCache(List<ProviderModel> models, Instant expiresAt) {
    }
}
