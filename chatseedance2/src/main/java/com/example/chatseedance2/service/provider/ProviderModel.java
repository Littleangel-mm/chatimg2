package com.example.chatseedance2.service.provider;

import java.math.BigDecimal;
import java.util.List;

public record ProviderModel(
        String id,
        String name,
        String category,
        List<String> flowTypes,
        List<String> inputKeys,
        BigDecimal cost
) {
    public boolean supportsVideo() {
        return flowTypes != null && flowTypes.stream().anyMatch(flow -> flow.endsWith("-to-video"));
    }
}
