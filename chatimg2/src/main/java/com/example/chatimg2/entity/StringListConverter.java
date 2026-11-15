package com.example.chatimg2.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;

@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<String> value) {
        try {
            return value == null ? null : JSON.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid source image list", error);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return JSON.readValue(value, new TypeReference<List<String>>() {});
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid stored source image list", error);
        }
    }
}
