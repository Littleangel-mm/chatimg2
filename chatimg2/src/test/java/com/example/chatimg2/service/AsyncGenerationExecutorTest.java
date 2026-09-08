package com.example.chatimg2.service;

import com.example.chatimg2.config.ImageStorageConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AsyncGenerationExecutorTest {

    @Mock
    private ImageApiClient imageApiClient;
    @Mock
    private ImageResultDeliveryService imageResultDeliveryService;
    @Mock
    private GenerationRecordService recordService;
    @Mock
    private ImageStorageConfig storageConfig;
    @Mock
    private DistributedLockService distributedLockService;
    @Mock
    private com.example.chatimg2.repository.GenerationRecordRepository recordRepository;

    @InjectMocks
    private AsyncGenerationExecutor executor;

    @Test
    void readsEveryPersistedReference(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var first = directory.resolve("first.png");
        var second = directory.resolve("second.jpg");
        java.nio.file.Files.write(first, new byte[]{1, 2});
        java.nio.file.Files.write(second, new byte[]{3, 4});
        var record = new com.example.chatimg2.entity.GenerationRecord();
        record.setSourceImagePaths(List.of("sources/first.png", "sources/second.jpg"));
        when(recordRepository.findById(12)).thenReturn(java.util.Optional.of(record));
        when(storageConfig.resolveSourceImageFile("sources/first.png")).thenReturn(first);
        when(storageConfig.resolveSourceImageFile("sources/second.jpg")).thenReturn(second);
        ReflectionTestUtils.setField(executor, "img2imgModel", "gpt-image-2");
        when(imageApiClient.imageEdit(org.mockito.ArgumentMatchers.eq("combine"),
                org.mockito.ArgumentMatchers.<byte[]>anyList(), org.mockito.ArgumentMatchers.eq("gpt-image-2")))
                .thenReturn("https://cdn.example/result.png");
        ReflectionTestUtils.invokeMethod(executor, "runLockedGeneration",
                12, "TASK-1", "combine", "img2img", "sources/first.png", null, System.currentTimeMillis());
        verify(imageApiClient).imageEdit(org.mockito.ArgumentMatchers.eq("combine"),
                org.mockito.ArgumentMatchers.<List<byte[]>>argThat(images -> images.size() == 2
                    && java.util.Arrays.equals(images.get(0), new byte[]{1, 2})
                    && java.util.Arrays.equals(images.get(1), new byte[]{3, 4})),
                org.mockito.ArgumentMatchers.eq("gpt-image-2"));
    }

    @Test
    void storesRemoteResultBeforeQueueingLocalBackup() {
        ReflectionTestUtils.setField(executor, "text2imgModel", "gpt-image-2");
        String remoteUrl = "https://cdn.example/result.png";
        when(imageApiClient.textToImage("提示词", "gpt-image-2", List.of())).thenReturn(remoteUrl);

        ReflectionTestUtils.invokeMethod(executor, "runLockedGeneration",
                12, "TASK-1", "提示词", "text2img", null, "user@qq.com", System.currentTimeMillis());

        InOrder order = inOrder(imageApiClient, recordService, imageResultDeliveryService);
        order.verify(imageApiClient).textToImage("提示词", "gpt-image-2", List.of());
        order.verify(recordService).markRemoteImageAvailable(12, remoteUrl);
        order.verify(imageResultDeliveryService).retrySavedRemoteImage(12);
    }

    @Test
    void keepsCompletedRemoteResultWhenBackupQueueRejectsTask() {
        ReflectionTestUtils.setField(executor, "text2imgModel", "gpt-image-2");
        String remoteUrl = "https://cdn.example/result.png";
        when(imageApiClient.textToImage("提示词", "gpt-image-2", List.of())).thenReturn(remoteUrl);
        doThrow(new IllegalStateException("backup queue full"))
                .when(imageResultDeliveryService).retrySavedRemoteImage(12);

        ReflectionTestUtils.invokeMethod(executor, "runLockedGeneration",
                12, "TASK-1", "提示词", "text2img", null, "user@qq.com", System.currentTimeMillis());

        verify(recordService).markRemoteImageAvailable(12, remoteUrl);
        verify(imageResultDeliveryService).retrySavedRemoteImage(12);
    }
}
