package com.example.chatseedance2.service;

import com.example.chatseedance2.config.SeedanceApiProperties;
import com.example.chatseedance2.dto.VideoModelResponse;
import com.example.chatseedance2.exception.BusinessException;
import com.example.chatseedance2.service.provider.BagegeVideoApiClient;
import com.example.chatseedance2.service.provider.ProviderModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class VideoModelService {

    private final BagegeVideoApiClient apiClient;
    private final SeedanceApiProperties properties;

    private volatile ModelCache cache;

    public List<VideoModelResponse> listVideoModels() {
        return models().stream()
                .filter(ProviderModel::supportsVideo)
                .map(model -> new VideoModelResponse(
                        model.id(), model.name(), model.category(),
                        model.flowTypes(), model.inputKeys(), model.cost()))
                .toList();
    }

    public Quote quote(String requestedModel, int durationSeconds) {
        String modelId = requestedModel == null || requestedModel.isBlank()
                ? properties.model()
                : requestedModel.trim();
        ProviderModel model = models().stream()
                .filter(item -> modelId.equals(item.id()))
                .findFirst()
                .orElseThrow(() -> new BusinessException("MODEL_UNAVAILABLE", "当前模型不可用，请刷新模型列表"));
        if (!model.supportsVideo()) {
            throw new BusinessException("MODEL_NOT_VIDEO", "所选模型不支持视频生成");
        }
        if (model.cost() == null || model.cost().signum() <= 0) {
            throw new BusinessException("MODEL_PRICE_UNAVAILABLE", "暂时无法获取模型价格，请稍后重试");
        }
        int credits = model.cost().multiply(BigDecimal.valueOf(durationSeconds))
                .setScale(0, RoundingMode.CEILING).intValueExact();
        return new Quote(model, credits);
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
