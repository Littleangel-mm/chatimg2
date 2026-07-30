package com.example.chatseedance2.service.provider;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ProviderTaskSnapshot(
        String id,
        String status,
        String model,
        String type,
        BigDecimal cost,
        List<String> resultUrls,
        ProviderError error,
        Map<String, Object> raw
) {
    public record ProviderError(String code, String message, String reason, String suggestion) {
    }
}
