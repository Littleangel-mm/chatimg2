package com.example.chatseedance2.service;

import com.example.chatseedance2.dto.VideoAssetInput;
import com.example.chatseedance2.dto.VideoGenerationRequest;
import com.example.chatseedance2.entity.UserAccount;
import com.example.chatseedance2.entity.VideoAssetMediaType;
import com.example.chatseedance2.entity.VideoAssetRole;
import com.example.chatseedance2.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class VideoRequestPreparationServiceTest {

    private VideoRequestPreparationService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        service = new VideoRequestPreparationService(mock(VideoStorageService.class), new ObjectMapper());
        user = new UserAccount();
        user.setId(1);
        user.setEmail("video@example.com");
    }

    @Test
    void preparesNineImagesWithSceneAndCharacterRoles() {
        List<VideoAssetInput> assets = imageAssets(9);

        PreparedVideoRequest prepared = service.prepare(request(assets), user);

        assertThat(prepared.assets()).hasSize(9);
        assertThat(prepared.assets()).extracting(PreparedVideoRequest.Asset::role)
                .containsExactly(
                        VideoAssetRole.BACKGROUND,
                        VideoAssetRole.SUBJECT, VideoAssetRole.SUBJECT, VideoAssetRole.SUBJECT,
                        VideoAssetRole.SUBJECT, VideoAssetRole.SUBJECT, VideoAssetRole.SUBJECT,
                        VideoAssetRole.SUBJECT, VideoAssetRole.SUBJECT);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> references =
                (List<Map<String, Object>>) prepared.providerPayload().get("image_references");
        assertThat(references).hasSize(9);
        assertThat(references.get(0)).containsEntry("ref_name", "image1").containsEntry("type", "background");
        assertThat(references.get(8)).containsEntry("ref_name", "image9").containsEntry("type", "subject");
    }

    @Test
    void rejectsMoreThanNineImages() {
        assertThatThrownBy(() -> service.prepare(request(imageAssets(10)), user))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("TOO_MANY_IMAGES"));
    }

    @Test
    void usesReferenceVideoUrlsForTyModel() {
        List<VideoAssetInput> assets = List.of(new VideoAssetInput(
                VideoAssetMediaType.VIDEO,
                VideoAssetRole.REFERENCE,
                "https://cdn.example.com/previous.mp4",
                null,
                0));

        PreparedVideoRequest prepared = service.prepare(request(assets), user);

        assertThat(prepared.providerPayload())
                .containsEntry("reference_video_urls", List.of("https://cdn.example.com/previous.mp4"))
                .doesNotContainKey("video_urls");
    }

    private VideoGenerationRequest request(List<VideoAssetInput> assets) {
        return new VideoGenerationRequest(
                "Create a group scene", "ty-sd2-mini-15s", 5,
                "16:9", "720p", "message-1", assets);
    }

    private List<VideoAssetInput> imageAssets(int count) {
        List<VideoAssetInput> assets = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            assets.add(new VideoAssetInput(
                    VideoAssetMediaType.IMAGE,
                    index == 0 ? VideoAssetRole.BACKGROUND : VideoAssetRole.SUBJECT,
                    "https://cdn.example.com/reference-" + index + ".png",
                    null,
                    index));
        }
        return assets;
    }
}
