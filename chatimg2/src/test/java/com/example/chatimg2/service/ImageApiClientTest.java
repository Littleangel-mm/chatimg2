package com.example.chatimg2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ImageApiClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void prefersAlreadyReturnedImageContentOverRemoteUrl() {
        ImageBackupService backupService = mock(ImageBackupService.class);
        ImageApiClient client = new ImageApiClient(backupService);
        when(backupService.saveBase64("encoded-image")).thenReturn("temporary-image.png");

        ObjectNode result = new ObjectMapper().createObjectNode();
        result.put("url", "https://unreachable.example/image.png");
        result.put("b64_json", "encoded-image");
        ArrayNode data = new ObjectMapper().createArrayNode().add(result);

        String image = ReflectionTestUtils.invokeMethod(client, "extractImageFromDataArray", data);

        assertThat(image).isEqualTo("temporary-image.png");
        verify(backupService).saveBase64("encoded-image");
    }

    @Test
    void usesRemoteUrlWhenResponseContainsNoImageContent() {
        ImageBackupService backupService = mock(ImageBackupService.class);
        ImageApiClient client = new ImageApiClient(backupService);
        ObjectNode result = new ObjectMapper().createObjectNode();
        result.put("url", "https://cdn.example/image.png");
        ArrayNode data = new ObjectMapper().createArrayNode().add(result);

        String image = ReflectionTestUtils.invokeMethod(client, "extractImageFromDataArray", data);

        assertThat(image).isEqualTo("https://cdn.example/image.png");
        verifyNoInteractions(backupService);
    }

    @Test
    void textToImageUsesHistoricalRequestContract() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = startServer("/v1/images/generations", captured);
        try {
            ImageApiClient client = configuredClient(server);

            client.textToImage("测试提示词", "gpt-image-2", List.of());

            CapturedRequest request = captured.get();
            var json = objectMapper.readTree(request.body());
            assertThat(json.get("model").asText()).isEqualTo("gpt-image-2");
            assertThat(json.get("size").asText()).isEqualTo("1024x1024");
            assertThat(json.get("quality").asText()).isEqualTo("medium");
            assertThat(json.get("n").asInt()).isEqualTo(1);
            assertThat(request.idempotencyKey()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void imageEditUsesHistoricalRequestContract() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = startServer("/v1/images/edits", captured);
        try {
            ImageApiClient client = configuredClient(server);

            client.imageEdit("修改图片", new byte[]{1, 2, 3}, "gpt-image-2");

            CapturedRequest request = captured.get();
            assertThat(request.body())
                    .contains("name=\"image\"; filename=\"source.png\"")
                    .contains("Content-Type: image/png")
                    .contains("name=\"size\"")
                    .contains("1024x1024");
            assertThat(request.idempotencyKey()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void recognizesFullWidthPortraitRatioInTextPrompt() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = startServer("/v1/images/generations", captured);
        try {
            ImageApiClient client = configuredClient(server);

            client.textToImage("古风少女，电影级光影，9：16", "gpt-image-2", List.of());

            var json = objectMapper.readTree(captured.get().body());
            assertThat(json.get("size").asText()).isEqualTo("1024x1536");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void recognizesFullWidthLandscapeRatioInImageEditPrompt() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = startServer("/v1/images/edits", captured);
        try {
            ImageApiClient client = configuredClient(server);

            client.imageEdit("调整为电影横版，16：9", new byte[]{1, 2, 3}, "gpt-image-2");

            assertThat(captured.get().body()).contains("1536x1024");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsFullWidthFourByThreeToSupportedLandscapeSize() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = startServer("/v1/images/generations", captured);
        try {
            ImageApiClient client = configuredClient(server);

            client.textToImage("古风场景，4：3", "gpt-image-2", List.of());

            var json = objectMapper.readTree(captured.get().body());
            assertThat(json.get("size").asText()).isEqualTo("1536x1024");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsFullWidthThreeByFourToSupportedPortraitSize() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = startServer("/v1/images/generations", captured);
        try {
            ImageApiClient client = configuredClient(server);

            client.textToImage("角色立绘，3：4", "gpt-image-2", List.of());

            var json = objectMapper.readTree(captured.get().body());
            assertThat(json.get("size").asText()).isEqualTo("1024x1536");
        } finally {
            server.stop(0);
        }
    }

    private ImageApiClient configuredClient(HttpServer server) {
        ImageApiClient client = new ImageApiClient(mock(ImageBackupService.class));
        ReflectionTestUtils.setField(client, "apiKey", "test-key");
        ReflectionTestUtils.setField(client, "apiUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(client, "submitMaxAttempts", 1);
        ReflectionTestUtils.setField(client, "connectTimeoutMs", 5000L);
        ReflectionTestUtils.setField(client, "submitReadTimeoutMs", 5000L);
        ReflectionTestUtils.setField(client, "pollReadTimeoutMs", 5000L);
        ReflectionTestUtils.setField(client, "maitokensWwwToWcf", false);
        client.initHttpClients();
        return client;
    }

    private HttpServer startServer(String path, AtomicReference<CapturedRequest> captured) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            captured.set(new CapturedRequest(
                    new String(requestBody, StandardCharsets.ISO_8859_1),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key")));
            byte[] response = "{\"data\":[{\"url\":\"https://cdn.example/result.png\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private record CapturedRequest(String body, String idempotencyKey) {
    }
}
