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

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AsyncGenerationExecutorTest {

    @Mock
    private ImageApiClient imageApiClient;
    @Mock
    private ImageBackupService imageBackupService;
    @Mock
    private GenerationRecordService recordService;
    @Mock
    private ImageStorageConfig storageConfig;
    @Mock
    private DistributedLockService distributedLockService;

    @InjectMocks
    private AsyncGenerationExecutor executor;

    @Test
    void downloadsAndSavesImageBeforeMarkingGenerationCompleted() {
        ReflectionTestUtils.setField(executor, "text2imgModel", "gpt-image-2");
        String remoteUrl = "https://cdn.example/result.png";
        when(imageApiClient.textToImage("提示词", "gpt-image-2", List.of())).thenReturn(remoteUrl);
        when(imageBackupService.backupSync(remoteUrl, "TASK-1", "user@qq.com"))
                .thenReturn(new ImageBackupService.BackupResult("user@qq.com/TASK-1.png"));

        ReflectionTestUtils.invokeMethod(executor, "runLockedGeneration",
                12, "TASK-1", "提示词", "text2img", null, "user@qq.com", System.currentTimeMillis());

        InOrder order = inOrder(imageApiClient, imageBackupService, recordService);
        order.verify(imageApiClient).textToImage("提示词", "gpt-image-2", List.of());
        order.verify(imageBackupService).backupSync(remoteUrl, "TASK-1", "user@qq.com");
        order.verify(recordService).markLocalImageAvailable(12, "user@qq.com/TASK-1.png");
        verify(recordService, never()).markRemoteImageAvailable(12, remoteUrl);
    }
}
