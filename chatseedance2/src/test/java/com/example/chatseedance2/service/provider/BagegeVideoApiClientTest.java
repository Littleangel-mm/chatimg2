package com.example.chatseedance2.service.provider;

import com.example.chatseedance2.config.SeedanceApiProperties;
import com.example.chatseedance2.exception.ProviderApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BagegeVideoApiClientTest {

    private MockWebServer server;
    private BagegeVideoApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        SeedanceApiProperties properties = new SeedanceApiProperties(
                server.url("/openai/v1").toString(), "test-key", "ty-sd2-mini-15s",
                3000, 3000, 3000);
        client = new BagegeVideoApiClient(new OkHttpClient(), new ObjectMapper(), properties);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void parsesModelsAndSendsBearerToken() throws Exception {
        server.enqueue(json(200, """
                {"data":[{"id":"ty-sd2-mini-15s","name":"Seedance 2.0 Mini 15s","category":"video",
                "flow_types":["text-to-video"],"input_keys":["prompt"],"cost":"17"}]}
                """));

        assertThat(client.listModels()).singleElement().satisfies(model -> {
            assertThat(model.id()).isEqualTo("ty-sd2-mini-15s");
            assertThat(model.cost()).isEqualByComparingTo("17");
        });
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer test-key");
    }

    @Test
    void parsesCompletedTaskResult() {
        server.enqueue(json(200, """
                {"id":"video_123","status":"completed","model":"ty-sd2-mini-15s","cost":68,
                "results":[{"url":"https://cdn.example.com/result.mp4"}]}
                """));

        ProviderTaskSnapshot result = client.submit(Map.of("prompt", "test"));

        assertThat(result.id()).isEqualTo("video_123");
        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.resultUrls()).containsExactly("https://cdn.example.com/result.mp4");
    }

    @Test
    void uncertainServerFailureIsClassifiedAsAmbiguousSubmission() {
        server.enqueue(json(503, "{\"error\":{\"code\":\"upstream_unavailable\",\"message\":\"busy\"}}"));

        assertThatThrownBy(() -> client.submit(Map.of("prompt", "test")))
                .isInstanceOfSatisfying(ProviderApiException.class, error -> {
                    assertThat(error.isRetryable()).isTrue();
                    assertThat(error.isAmbiguousSubmission()).isTrue();
                });
    }

    @Test
    void definitiveServerRejectionIsNotClassifiedAsAmbiguous() {
        server.enqueue(json(503, "{\"error\":{\"code\":\"model_not_found\",\"message\":\"missing\"}}"));

        assertThatThrownBy(() -> client.submit(Map.of("prompt", "test")))
                .isInstanceOfSatisfying(ProviderApiException.class, error -> {
                    assertThat(error.isRetryable()).isTrue();
                    assertThat(error.isAmbiguousSubmission()).isFalse();
                });
    }

    private MockResponse json(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
