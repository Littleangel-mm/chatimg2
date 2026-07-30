package com.example.chatseedance2.service;

import com.example.chatseedance2.config.VideoProperties;
import com.example.chatseedance2.entity.VideoGenerationTask;
import com.example.chatseedance2.entity.VideoTaskStatus;
import com.example.chatseedance2.repository.VideoGenerationTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.video.recovery-enabled", havingValue = "true", matchIfMissing = true)
public class VideoTaskRecoveryService {

    private static final List<VideoTaskStatus> RECOVERABLE = List.of(
            VideoTaskStatus.SUBMITTING,
            VideoTaskStatus.PENDING,
            VideoTaskStatus.PROCESSING,
            VideoTaskStatus.SUBMISSION_UNKNOWN,
            VideoTaskStatus.POLLING_DELAYED);

    private final VideoGenerationTaskRepository taskRepository;
    private final VideoTaskExecutionService executionService;
    private final VideoProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        dispatch("startup");
    }

    @Scheduled(
            fixedDelayString = "${app.video.poll-interval-ms:5000}",
            initialDelayString = "${app.video.poll-interval-ms:5000}"
    )
    public void recoverOnSchedule() {
        dispatch("scheduled");
    }

    private void dispatch(String trigger) {
        List<VideoGenerationTask> tasks = taskRepository.findReadyForRecovery(
                RECOVERABLE,
                LocalDateTime.now(),
                PageRequest.of(0, Math.max(1, properties.pollBatchSize())));
        for (VideoGenerationTask task : tasks) {
            try {
                if (task.getProviderTaskId() == null) {
                    executionService.submitAsync(task.getId());
                } else {
                    executionService.pollAsync(task.getId());
                }
            } catch (TaskRejectedException error) {
                log.warn("Video executor queue full: taskCode={}, trigger={}", task.getTaskCode(), trigger);
                break;
            }
        }
    }
}
