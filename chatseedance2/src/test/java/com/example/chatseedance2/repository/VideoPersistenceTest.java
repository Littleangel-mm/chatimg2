package com.example.chatseedance2.repository;

import com.example.chatseedance2.entity.VideoAssetDirection;
import com.example.chatseedance2.entity.VideoAssetMediaType;
import com.example.chatseedance2.entity.VideoAssetRole;
import com.example.chatseedance2.entity.VideoBillingStatus;
import com.example.chatseedance2.entity.VideoGenerationAsset;
import com.example.chatseedance2.entity.VideoGenerationTask;
import com.example.chatseedance2.entity.VideoGenerationType;
import com.example.chatseedance2.entity.VideoTaskEvent;
import com.example.chatseedance2.entity.VideoTaskEventType;
import com.example.chatseedance2.entity.VideoTaskStatus;
import com.example.chatseedance2.service.VideoTaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class VideoPersistenceTest {

    @Autowired
    private VideoGenerationTaskRepository taskRepository;

    @Autowired
    private VideoGenerationAssetRepository assetRepository;

    @Autowired
    private VideoTaskEventRepository eventRepository;

    @Test
    void persistsTaskAssetsAndEvents() {
        VideoGenerationTask task = newTask("VIDEO-20260730-ABCDEF01", "message-1");
        task.setRequestPayload(Map.of(
                "model", "ty-p-sd2-fast-720",
                "duration", 4,
                "aspect_ratio", "9:16"));

        VideoGenerationTask savedTask = taskRepository.saveAndFlush(task);

        VideoGenerationAsset asset = new VideoGenerationAsset();
        asset.setTaskId(savedTask.getId());
        asset.setDirection(VideoAssetDirection.INPUT);
        asset.setMediaType(VideoAssetMediaType.IMAGE);
        asset.setAssetRole(VideoAssetRole.SUBJECT);
        asset.setSourceUrl("https://cdn.example.com/person.png");
        asset.setLocalPath("inputs/user@example.com/person.png");
        asset.setSortOrder(0);
        assetRepository.saveAndFlush(asset);

        VideoTaskEvent event = new VideoTaskEvent();
        event.setTaskId(savedTask.getId());
        event.setEventType(VideoTaskEventType.CREATED);
        event.setToStatus(VideoTaskStatus.SUBMITTING);
        event.setPayload(Map.of("source", "test"));
        eventRepository.saveAndFlush(event);

        VideoGenerationTask reloaded = taskRepository.findByTaskCode(savedTask.getTaskCode()).orElseThrow();
        assertThat(reloaded.getRequestPayload()).containsEntry("model", "ty-p-sd2-fast-720");
        assertThat(reloaded.getVersion()).isNotNull();
        assertThat(assetRepository.findByTaskIdOrderBySortOrderAsc(savedTask.getId()))
                .extracting(VideoGenerationAsset::getAssetRole)
                .containsExactly(VideoAssetRole.SUBJECT);
        assertThat(new VideoTaskMapper(assetRepository).toResponse(reloaded).inputAssets())
                .singleElement()
                .satisfies(input -> {
                    assertThat(input.sourceUrl())
                            .isEqualTo("/video-media/inputs/user@example.com/person.png");
                    assertThat(input.previewPath())
                            .isEqualTo("/video/generations/VIDEO-20260730-ABCDEF01/inputs/" + asset.getId());
                });
        assertThat(eventRepository.findTop100ByTaskIdOrderByCreatedAtDesc(savedTask.getId()))
                .extracting(VideoTaskEvent::getEventType)
                .containsExactly(VideoTaskEventType.CREATED);
    }

    @Test
    void selectsOnlyNonTerminalTasksThatAreReadyForRecovery() {
        VideoGenerationTask ready = newTask("VIDEO-20260730-ABCDEF02", "message-2");
        ready.setStatus(VideoTaskStatus.PROCESSING);
        ready.setProviderTaskId("job_ready");
        ready.setNextPollAt(LocalDateTime.now().minusSeconds(1));
        taskRepository.save(ready);

        VideoGenerationTask later = newTask("VIDEO-20260730-ABCDEF03", "message-3");
        later.setStatus(VideoTaskStatus.PENDING);
        later.setProviderTaskId("job_later");
        later.setNextPollAt(LocalDateTime.now().plusMinutes(5));
        taskRepository.save(later);

        VideoGenerationTask completed = newTask("VIDEO-20260730-ABCDEF04", "message-4");
        completed.setStatus(VideoTaskStatus.COMPLETED);
        completed.setProviderTaskId("job_completed");
        completed.setNextPollAt(LocalDateTime.now().minusMinutes(1));
        taskRepository.saveAndFlush(completed);

        List<VideoGenerationTask> recoverable = taskRepository.findReadyForRecovery(
                List.of(
                        VideoTaskStatus.SUBMITTING,
                        VideoTaskStatus.PENDING,
                        VideoTaskStatus.PROCESSING,
                        VideoTaskStatus.SUBMISSION_UNKNOWN,
                        VideoTaskStatus.POLLING_DELAYED),
                LocalDateTime.now(),
                PageRequest.of(0, 100));

        assertThat(recoverable)
                .extracting(VideoGenerationTask::getProviderTaskId)
                .containsExactly("job_ready");
    }

    private VideoGenerationTask newTask(String taskCode, String clientMessageId) {
        VideoGenerationTask task = new VideoGenerationTask();
        task.setTaskCode(taskCode);
        task.setClientMessageId(clientMessageId);
        task.setUserId(1);
        task.setUserEmail("user@example.com");
        task.setModel("ty-p-sd2-fast-720");
        task.setGenerationType(VideoGenerationType.TEXT_TO_VIDEO);
        task.setPrompt("A cinematic camera movement");
        task.setDurationSeconds(4);
        task.setAspectRatio("9:16");
        task.setResolution("720p");
        task.setStatus(VideoTaskStatus.SUBMITTING);
        task.setBillingStatus(VideoBillingStatus.RESERVED);
        task.setQuotedUnitCost(new BigDecimal("17"));
        task.setReservedCredits(68);
        task.setChargedCredits(0);
        return task;
    }
}
