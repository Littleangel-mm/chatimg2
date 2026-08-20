package com.example.chatseedance2.service;

import com.example.chatseedance2.config.VideoProperties;
import com.example.chatseedance2.dto.UploadedVideoAssetResponse;
import com.example.chatseedance2.entity.UserAccount;
import com.example.chatseedance2.entity.VideoAssetMediaType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class VideoStorageServiceTest {

    @TempDir
    Path storageRoot;

    private VideoStorageService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        VideoProperties properties = new VideoProperties(
                3000, 5000, 30000, 50, 180000,
                new VideoProperties.Executor(2, 8, 100),
                storageRoot.toString(), "https://chatimg2.cn/",
                20 * 1024 * 1024L, 500 * 1024 * 1024L, 100 * 1024 * 1024L);
        service = new VideoStorageService(properties);
        service.initialize();
        user = new UserAccount();
        user.setEmail("video@example.com");
    }

    @Test
    void storesUploadedImageAndReturnsProviderDownloadUrl() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "file", "reference.png", "image/png", new byte[]{1, 2, 3, 4});

        UploadedVideoAssetResponse uploaded = service.storeInput(
                image, VideoAssetMediaType.IMAGE, user, "http://localhost:8080");

        assertThat(uploaded.localPath()).startsWith("inputs/video@example.com/").endsWith(".png");
        String publicUrl = "https://chatimg2.cn/video-media/" + uploaded.localPath();
        assertThat(uploaded.sourceUrl()).isEqualTo(publicUrl);
        assertThat(Files.readAllBytes(service.resolve(uploaded.localPath())))
                .containsExactly(1, 2, 3, 4);
        assertThat(service.requireOwnedPublicUrl(uploaded.localPath(), user))
                .isEqualTo(publicUrl);
    }
}
