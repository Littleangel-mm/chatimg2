package com.example.chatseedance2.service;

import com.example.chatseedance2.config.VideoProperties;
import com.example.chatseedance2.entity.VideoGenerationTask;
import com.example.chatseedance2.exception.ProviderApiException;
import com.example.chatseedance2.repository.VideoGenerationTaskRepository;
import com.example.chatseedance2.service.provider.BagegeVideoApiClient;
import com.example.chatseedance2.service.provider.ProviderTaskSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoTaskExecutionService {

    private final BagegeVideoApiClient apiClient;
    private final VideoTaskRecordService recordService;
    private final VideoGenerationTaskRepository taskRepository;
    private final DistributedLockService lockService;
    private final VideoProperties properties;

    @Async("videoTaskExecutor")
    public void submitAsync(Long taskId) {
        withTaskLock(taskId, () -> submitLocked(taskId));
    }

    @Async("videoTaskExecutor")
    public void pollAsync(Long taskId) {
        withTaskLock(taskId, () -> pollLocked(taskId));
    }

    private void submitLocked(Long taskId) {
        Optional<VideoTaskRecordService.Submission> submission = recordService.beginSubmission(taskId);
        if (submission.isEmpty()) {
            return;
        }
        VideoTaskRecordService.Submission work = submission.get();
        try {
            ProviderTaskSnapshot snapshot = apiClient.submit(work.payload());
            if ("failed".equals(snapshot.status())) {
                recordService.fail(taskId, snapshot.error(), "PROVIDER_FAILED", "供应商拒绝了视频任务", snapshot.raw());
            } else if ("completed".equals(snapshot.status()) && !snapshot.resultUrls().isEmpty()) {
                recordService.complete(taskId, snapshot);
            } else {
                recordService.markAccepted(taskId, snapshot);
            }
            log.info("Video provider submission handled: taskCode={}, providerTaskId={}, status={}",
                    work.taskCode(), snapshot.id(), snapshot.status());
        } catch (ProviderApiException error) {
            if (error.isAmbiguousSubmission()) {
                recordService.markSubmissionUnknown(taskId,
                        error.getProviderErrorCode() == null ? "SUBMISSION_UNKNOWN" : error.getProviderErrorCode(),
                        error.getMessage(), null);
                log.warn("Video submission outcome unknown: taskCode={}", work.taskCode(), error);
            } else {
                recordService.fail(taskId, null,
                        error.getProviderErrorCode() == null ? "SUBMIT_FAILED" : error.getProviderErrorCode(),
                        error.getMessage(), null);
                log.error("Video submission failed definitively: taskCode={}", work.taskCode(), error);
            }
        } catch (RuntimeException error) {
            recordService.markSubmissionUnknown(taskId, "SUBMISSION_UNKNOWN", error.getMessage(), null);
            log.error("Unexpected video submission failure: taskCode={}", work.taskCode(), error);
        }
    }

    private void pollLocked(Long taskId) {
        VideoGenerationTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null || task.getStatus().isTerminal() || task.getProviderTaskId() == null) {
            return;
        }
        try {
            ProviderTaskSnapshot snapshot = apiClient.getTask(task.getProviderTaskId());
            switch (snapshot.status() == null ? "" : snapshot.status()) {
                case "completed" -> {
                    if (snapshot.resultUrls().isEmpty()) {
                        recordService.markPollDelayed(taskId, "RESULT_NOT_READY",
                                "供应商已完成但结果地址暂未就绪", null, true, snapshot.raw());
                    } else {
                        recordService.complete(taskId, snapshot);
                    }
                }
                case "failed" -> recordService.fail(taskId, snapshot.error(),
                        "PROVIDER_FAILED", "视频生成失败", snapshot.raw());
                case "pending", "processing" -> recordService.applyProgress(taskId, snapshot);
                default -> recordService.markPollDelayed(taskId, "UNKNOWN_PROVIDER_STATUS",
                        "供应商返回了未知任务状态", null, true, snapshot.raw());
            }
        } catch (ProviderApiException error) {
            recordService.markPollDelayed(taskId,
                    error.getProviderErrorCode() == null ? "POLL_FAILED" : error.getProviderErrorCode(),
                    error.getMessage(), error.getProviderHttpStatus(), error.isRetryable(), null);
            log.warn("Video task poll delayed: taskCode={}, providerTaskId={}",
                    task.getTaskCode(), task.getProviderTaskId(), error);
        } catch (RuntimeException error) {
            recordService.markPollDelayed(taskId, "POLL_FAILED", error.getMessage(), null, true, null);
            log.error("Unexpected video task poll failure: taskCode={}", task.getTaskCode(), error);
        }
    }

    private void withTaskLock(Long taskId, Runnable action) {
        String lockKey = "video:task:" + taskId;
        Optional<DistributedLockService.LockToken> token;
        try {
            token = lockService.tryAcquire(lockKey, Duration.ofMillis(properties.taskLockTtlMs()));
        } catch (RuntimeException error) {
            log.error("Acquire video task lock failed: taskId={}", taskId, error);
            return;
        }
        if (token.isEmpty()) {
            return;
        }
        try (DistributedLockService.LockLease ignored = lockService.keepAlive(
                token.get(), Duration.ofMillis(properties.taskLockTtlMs()))) {
            action.run();
        } finally {
            lockService.release(token.get());
        }
    }
}
