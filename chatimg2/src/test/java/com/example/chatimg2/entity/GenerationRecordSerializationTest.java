package com.example.chatimg2.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationRecordSerializationTest {

    @Test
    void exposesRemoteAvailabilityWithoutExposingSignedUrl() throws Exception {
        GenerationRecord record = new GenerationRecord();
        record.setRemoteImageUrl("https://cdn.example/image.png?signature=secret");

        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(record));

        assertThat(json.get("remoteImageAvailable").asBoolean()).isTrue();
        assertThat(json.has("remoteImageUrl")).isFalse();
        assertThat(json.toString()).doesNotContain("signature=secret");
    }

    @Test
    void exposesWhetherTheBackendCanStillRecoverTheGeneration() throws Exception {
        GenerationRecord record = new GenerationRecord();
        record.setStatus("failed");
        record.setErrorMessage("生成任务中断，已退回积分，请重新生成");

        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(record));

        assertThat(json.get("recoverable").asBoolean()).isTrue();
    }
}
