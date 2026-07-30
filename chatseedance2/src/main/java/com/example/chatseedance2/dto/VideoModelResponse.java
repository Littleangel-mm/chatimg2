package com.example.chatseedance2.dto;

import java.math.BigDecimal;
import java.util.List;

public record VideoModelResponse(
        String id,
        String name,
        String category,
        List<String> flowTypes,
        List<String> inputKeys,
        BigDecimal costPerSecond
) {
}
