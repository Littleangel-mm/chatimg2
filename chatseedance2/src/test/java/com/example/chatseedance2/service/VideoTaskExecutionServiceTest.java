package com.example.chatseedance2.service;

import com.example.chatseedance2.config.VideoProperties;
import com.example.chatseedance2.repository.VideoGenerationTaskRepository;
import com.example.chatseedance2.service.provider.BagegeVideoApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoTaskExecutionServiceTest {

    @Mock
    private BagegeVideoApiClient apiClient;
    @Mock
    private VideoTaskRecordService recordService;
    @Mock
    private VideoGenerationTaskRepository taskRepository;
    @Mock
    private DistributedLockService lockService;
    @Mock
    private Executor taskExecutor;

    private VideoTaskExecutionService executionService;

    @BeforeEach
    void setUp() {
        VideoProperties properties = new VideoProperties(
                3_000, 5_000, 30_000, 100, 60_000,
                new VideoProperties.Executor(2, 8, 100),
                "video-media", "", 10_000_000, 100_000_000, 20_000_000);
        executionService = new VideoTaskExecutionService(
                apiClient, recordService, taskRepository, lockService, properties, taskExecutor);
    }

    @Test
    void queuesSameTaskOnlyOnceUntilCurrentDispatchFinishes() {
        VideoTaskRecordService.DispatchClaim claim = new VideoTaskRecordService.DispatchClaim(
                42L, LocalDateTime.now().plusMinutes(1));
        when(recordService.claimForDispatch(42L, VideoTaskRecordService.DispatchType.SUBMIT))
                .thenReturn(Optional.of(claim));

        assertThat(executionService.submitAsync(42L)).isTrue();
        assertThat(executionService.submitAsync(42L)).isFalse();

        verify(recordService).claimForDispatch(42L, VideoTaskRecordService.DispatchType.SUBMIT);
        verify(taskExecutor).execute(any(Runnable.class));
        verifyNoMoreInteractions(recordService, taskExecutor);
    }

    @Test
    void releasesDatabaseClaimWhenExecutorRejectsTask() {
        VideoTaskRecordService.DispatchClaim claim = new VideoTaskRecordService.DispatchClaim(
                43L, LocalDateTime.now().plusMinutes(1));
        when(recordService.claimForDispatch(43L, VideoTaskRecordService.DispatchType.POLL))
                .thenReturn(Optional.of(claim));
        doThrow(new TaskRejectedException("video queue full"))
                .when(taskExecutor).execute(any(Runnable.class));

        assertThatThrownBy(() -> executionService.pollAsync(43L))
                .isInstanceOf(TaskRejectedException.class)
                .hasMessageContaining("video queue full");

        verify(recordService).releaseDispatchClaim(claim);
    }
}
