package com.example.chatseedance2.service;

import com.example.chatseedance2.config.SeedanceApiProperties;
import com.example.chatseedance2.exception.BusinessException;
import com.example.chatseedance2.service.provider.BagegeVideoApiClient;
import com.example.chatseedance2.service.provider.ProviderModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoModelServiceTest {

    private BagegeVideoApiClient apiClient;
    private VideoModelService service;

    @BeforeEach
    void setUp() {
        apiClient = mock(BagegeVideoApiClient.class);
        SeedanceApiProperties properties = new SeedanceApiProperties(
                "https://example.invalid/openai/v1", "test-key", "69cf3df6f9e33413dbd1db17",
                3000, 3000, 300000);
        service = new VideoModelService(apiClient, properties);
    }

    @Test
    void exposesOnlyExactSeedanceModelWithFixedUserPrice() {
        when(apiClient.listModels()).thenReturn(List.of(
                videoModel("manxue-2.0", "Seedance 2.0 distributor alias"),
                videoModel("69cf3df6f9e33413dbd1db17", "Seedance 2.0 Fast 720p")));

        assertThat(service.listVideoModels())
                .singleElement()
                .satisfies(model -> {
                    assertThat(model.id()).isEqualTo("69cf3df6f9e33413dbd1db17");
                    assertThat(model.costPerSecond()).isEqualByComparingTo("100");
                });
    }

    @Test
    void chargesFiveHundredCreditsForFiveSeconds() {
        when(apiClient.listModels()).thenReturn(List.of(
                videoModel("69cf3df6f9e33413dbd1db17", "Seedance 2.0 Fast 720p")));

        assertThat(service.quote("69cf3df6f9e33413dbd1db17", 5).credits()).isEqualTo(500);
        assertThat(service.quote("69cf3df6f9e33413dbd1db17", 10).credits()).isEqualTo(1000);
        assertThat(service.quote("69cf3df6f9e33413dbd1db17", 15).credits()).isEqualTo(1500);
    }

    @Test
    void canonicalizesLegacyClientModelIdsToAvailableFastModel() {
        when(apiClient.listModels()).thenReturn(List.of(
                videoModel("69cf3df6f9e33413dbd1db17", "Seedance 2.0 Fast 720p")));

        assertThat(service.quote("ty-sd2-mini-15s", 5).model().id())
                .isEqualTo("69cf3df6f9e33413dbd1db17");
    }

    @Test
    void rejectsProviderAliasEvenWhenItsNameContainsSeedance() {
        assertThatThrownBy(() -> service.quote("manxue-2.0", 5))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("MODEL_NOT_ALLOWED"));
    }

    private ProviderModel videoModel(String id, String name) {
        return new ProviderModel(
                id, name, "text-to-video", List.of("text-to-video", "image-to-video"),
                List.of("prompt", "image_references"), new BigDecimal("17"));
    }
}
