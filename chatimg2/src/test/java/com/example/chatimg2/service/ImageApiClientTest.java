package com.example.chatimg2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ImageApiClientTest {

    @Test
    void prefersRemoteUrlLikeHistoricalSuccessfulBranches() {
        ImageBackupService backupService = mock(ImageBackupService.class);
        ImageApiClient client = new ImageApiClient(backupService);

        ObjectNode result = new ObjectMapper().createObjectNode();
        result.put("url", "https://unreachable.example/image.png");
        result.put("b64_json", "encoded-image");
        ArrayNode data = new ObjectMapper().createArrayNode().add(result);

        String image = ReflectionTestUtils.invokeMethod(client, "extractImageFromDataArray", data);

        assertThat(image).isEqualTo("https://unreachable.example/image.png");
        verifyNoInteractions(backupService);
    }
}
