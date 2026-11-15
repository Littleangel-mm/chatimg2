package com.example.chatimg2.service;

import com.example.chatimg2.exception.BusinessException;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ImageApiClientTest {

    private static final byte[] PNG_IMAGE = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x01};
    private static final byte[] JPEG_IMAGE = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01};

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
    void singleImageListPreservesHistoricalMultipartContract() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = startServer("/v1/images/edits", captured);
        try {
            ImageApiClient client = configuredClient(server);
            ReflectionTestUtils.setField(client, "apiUrl",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            client.imageEdit("edit", List.of(JPEG_IMAGE), "gpt-image-2");
            String body = captured.get().body();
            assertThat(body).contains("name=\"image\"; filename=\"source.jpg\"")
                    .doesNotContain("name=\"image[]\"");
            assertThat(body.indexOf("name=\"image\"")).isLessThan(body.indexOf("name=\"prompt\""));
            assertThat(body.indexOf("name=\"prompt\"")).isLessThan(body.indexOf("name=\"model\""));
            assertThat(body.indexOf("name=\"model\"")).isLessThan(body.indexOf("name=\"n\""));
            assertThat(body.indexOf("name=\"n\"")).isLessThan(body.indexOf("name=\"size\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void plainText520KeepsUpstreamStatusAndDoesNotResubmit() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/edits", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] response = "error code: 520".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(520, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ImageApiClient client = configuredClient(server);
            ReflectionTestUtils.setField(client, "submitMaxAttempts", 3);
            assertThatThrownBy(() -> client.imageEdit("edit", List.of(JPEG_IMAGE), "gpt-image-2"))
                    .isInstanceOfSatisfying(com.example.chatimg2.exception.ApiCallException.class, error -> {
                        assertThat(error.getHttpStatus()).isEqualTo(520);
                        assertThat(error.isRetryable()).isFalse();
                        assertThat(error.getApiErrorCode()).isEqualTo("bad_response_body");
                    });
            assertThat(requests.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void imageEditUsesDetectedJpegMediaType() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = startServer("/v1/images/edits", captured);
        try {
            ImageApiClient client = configuredClient(server);

            client.imageEdit("修改图片", JPEG_IMAGE, "gpt-image-2");

            CapturedRequest request = captured.get();
            assertThat(request.body())
                    .contains("name=\"image\"; filename=\"source.jpg\"")
                    .contains("Content-Type: image/jpeg")
                    .contains("name=\"size\"")
                    .contains("1024x1024");
            assertThat(request.idempotencyKey()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void imageEditRejectsUnknownImageContentBeforeCallingProvider() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = startServer("/v1/images/edits", captured);
        try {
            ImageApiClient client = configuredClient(server);

            assertThatThrownBy(() -> client.imageEdit("修改图片", new byte[]{1, 2, 3}, "gpt-image-2"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("参考图片格式无效");
            assertThat(captured.get()).isNull();
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

            client.imageEdit("调整为电影横版，16：9", PNG_IMAGE, "gpt-image-2");

            assertThat(captured.get().body()).contains("1536x1024");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void recognizesRatioSymbolPortraitRatioInImageEditPrompt() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = startServer("/v1/images/edits", captured);
        try {
            ImageApiClient client = configuredClient(server);

            client.imageEdit("realistic style, 9\u223616", PNG_IMAGE, "gpt-image-2");

            assertThat(captured.get().body()).contains("1024x1536");
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

    @Test
    void acceptsConfiguredBaseUrlWithVersionSuffix() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = startServer("/v1/images/generations", captured);
        try {
            ImageApiClient client = configuredClient(server);
            ReflectionTestUtils.setField(client, "apiUrl",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
            client.initHttpClients();

            String result = client.textToImage("测试图片", "gpt-image-2", List.of());

            assertThat(result).isEqualTo("https://cdn.example/result.png");
            assertThat(captured.get()).isNotNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesWhenDistributorTemporarilyHasNoAvailableChannel() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            int attempt = requests.incrementAndGet();
            String body;
            int status;
            if (attempt == 1) {
                status = 503;
                body = """
                        {"error":{"code":"model_not_found","type":"new_api_error",
                        "message":"No available channel for model gpt-image-2 under group GPT-Pro (distributor)"}}
                        """;
            } else {
                status = 200;
                body = "{\"data\":[{\"url\":\"https://cdn.example/result.png\"}]}";
            }
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ImageApiClient client = configuredClient(server);
            ReflectionTestUtils.setField(client, "submitMaxAttempts", 2);
            ReflectionTestUtils.setField(client, "submitRetryDelayMs", 0L);

            String result = client.textToImage("测试提示词", "gpt-image-2", List.of());

            assertThat(result).isEqualTo("https://cdn.example/result.png");
            assertThat(requests.get()).isEqualTo(2);
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
        client.initHttpClients();
        return client;
    }

    @Test
    void imageEditSendsAllImagesInOrder() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = startServer("/v1/images/edits", captured);
        try {
            configuredClient(server).imageEdit("combine", List.of(JPEG_IMAGE, PNG_IMAGE), "gpt-image-2");
            assertThat(captured.get().body())
                    .contains("name=\"image[]\"; filename=\"source-0.jpg\"")
                    .contains("name=\"image[]\"; filename=\"source-1.png\"");
            assertThat(captured.get().body().indexOf("source-0.jpg"))
                    .isLessThan(captured.get().body().indexOf("source-1.png"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsInvalidSecondImageWithoutCallingProvider() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = startServer("/v1/images/edits", captured);
        try {
            assertThatThrownBy(() -> configuredClient(server).imageEdit(
                    "combine", List.of(JPEG_IMAGE, new byte[]{1, 2}), "gpt-image-2"))
                    .isInstanceOf(BusinessException.class);
            assertThat(captured.get()).isNull();
        } finally {
            server.stop(0);
        }
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
