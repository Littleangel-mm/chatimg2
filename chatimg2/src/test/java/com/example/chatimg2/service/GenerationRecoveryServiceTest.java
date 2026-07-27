package com.example.chatimg2.service;

import com.example.chatimg2.config.ImageStorageConfig;
import com.example.chatimg2.entity.GenerationRecord;
import com.example.chatimg2.repository.GenerationRecordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerationRecoveryServiceTest {

    @Mock
    private DistributedLockService distributedLockService;
    @Mock
    private GenerationRecordService recordService;
    @Mock
    private GenerationRecordRepository recordRepository;
    @Mock
    private ImageStorageConfig storageConfig;
    @Mock
    private AuthService authService;

    @InjectMocks
    private GenerationRecoveryService recoveryService;

    @Test
    void startupRestoresInterruptedRecordWhenTaskImageAlreadyExists() {
        GenerationRecord record = new GenerationRecord();
        record.setId(206);
        record.setStatus(GenerationState.FAILED);
        record.setErrorMessage(GenerationState.INTERRUPTED_MESSAGE);
        record.setTaskCode("TASK-20260726-1B3E468B");
        record.setUserEmail("user@qq.com");
        String imagePath = "user@qq.com/TASK-20260726-1B3E468B.png";

        when(recordRepository
                .findTop50ByStatusAndImagePathIsNullAndErrorMessageContainingOrderByCreatedAtDesc(
                        GenerationState.FAILED, "生成任务中断"))
                .thenReturn(List.of(record));
        when(authService.safeEmailFolder("user@qq.com")).thenReturn("user@qq.com");
        when(storageConfig.resolveReadableImageFile(imagePath)).thenReturn(Path.of(imagePath));
        when(recordService.markLocalImageAvailable(206, imagePath)).thenReturn(true);

        recoveryService.recoverOnStartup();

        verify(recordService).markLocalImageAvailable(206, imagePath);
    }

    @Test
    void scheduledRecoveryNeverMarksRunningGenerationsAsInterrupted() {
        when(recordRepository
                .findTop50ByStatusAndImagePathIsNullAndErrorMessageContainingOrderByCreatedAtDesc(
                        GenerationState.FAILED, "生成任务中断"))
                .thenReturn(List.of());

        recoveryService.recoverOnSchedule();

        verify(recordRepository, never())
                .findTop50ByStatusAndImagePathIsNullAndCreatedAtBeforeOrderByCreatedAtAsc(
                        any(), any());
        verifyNoInteractions(distributedLockService, recordService);
    }
}
