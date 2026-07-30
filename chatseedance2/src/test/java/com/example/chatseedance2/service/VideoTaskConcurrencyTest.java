package com.example.chatseedance2.service;

import com.example.chatseedance2.entity.UserAccount;
import com.example.chatseedance2.entity.VideoBillingStatus;
import com.example.chatseedance2.entity.VideoGenerationTask;
import com.example.chatseedance2.entity.VideoGenerationType;
import com.example.chatseedance2.exception.ForbiddenException;
import com.example.chatseedance2.repository.SeedanceUserAccountRepository;
import com.example.chatseedance2.repository.VideoGenerationAssetRepository;
import com.example.chatseedance2.repository.VideoGenerationTaskRepository;
import com.example.chatseedance2.repository.VideoTaskEventRepository;
import com.example.chatseedance2.service.provider.ProviderModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class VideoTaskConcurrencyTest {

    @Autowired
    private VideoTaskRecordService recordService;

    @Autowired
    private VideoGenerationService generationService;

    @Autowired
    private SeedanceUserAccountRepository userRepository;

    @Autowired
    private VideoGenerationTaskRepository taskRepository;

    @Autowired
    private VideoGenerationAssetRepository assetRepository;

    @Autowired
    private VideoTaskEventRepository eventRepository;

    @BeforeEach
    void cleanDatabase() {
        eventRepository.deleteAll();
        assetRepository.deleteAll();
        taskRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void concurrentSameClientMessageCreatesAndChargesOnlyOnce() throws Exception {
        UserAccount user = createUser("concurrent@example.com");
        PreparedVideoRequest request = prepared("client-message-one", "fingerprint-one");
        VideoModelService.Quote quote = quote();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Callable<VideoTaskRecordService.CreateResult> action = () ->
                    recordService.create(user, request, quote);
            List<Future<VideoTaskRecordService.CreateResult>> futures = pool.invokeAll(
                    List.of(action, action, action, action));

            List<VideoTaskRecordService.CreateResult> results = futures.stream()
                    .map(this::get)
                    .toList();

            assertThat(results).filteredOn(VideoTaskRecordService.CreateResult::created).hasSize(1);
            assertThat(results).extracting(result -> result.task().getId()).containsOnly(
                    results.get(0).task().getId());
            assertThat(taskRepository.count()).isEqualTo(1);
            assertThat(userRepository.findById(user.getId()).orElseThrow().getUsedCredits()).isEqualTo(68);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sameFingerprintWithinDebounceWindowCreatesAndChargesOnlyOnce() {
        UserAccount user = createUser("debounce@example.com");

        VideoTaskRecordService.CreateResult first = recordService.create(
                user, prepared(null, "same-fingerprint"), quote());
        VideoTaskRecordService.CreateResult second = recordService.create(
                user, prepared(null, "same-fingerprint"), quote());

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.task().getId()).isEqualTo(first.task().getId());
        assertThat(taskRepository.count()).isEqualTo(1);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getUsedCredits()).isEqualTo(68);
    }

    @Test
    void concurrentFailureRefundsReservedCreditsOnlyOnce() throws Exception {
        UserAccount user = createUser("refund@example.com");
        VideoGenerationTask task = recordService.create(
                user, prepared("refund-message", "refund-fingerprint"), quote()).task();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> action = () -> recordService.fail(
                    task.getId(), null, "PROVIDER_FAILED", "provider failed", Map.of());
            List<Future<Boolean>> futures = pool.invokeAll(List.of(action, action));

            assertThat(futures.stream().map(this::get).filter(Boolean::booleanValue)).hasSize(1);
            VideoGenerationTask failed = taskRepository.findById(task.getId()).orElseThrow();
            assertThat(failed.getBillingStatus()).isEqualTo(VideoBillingStatus.REFUNDED);
            assertThat(userRepository.findById(user.getId()).orElseThrow().getUsedCredits()).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void userCannotReadAnotherUsersTask() {
        UserAccount owner = createUser("owner@example.com");
        UserAccount stranger = createUser("stranger@example.com");
        VideoGenerationTask task = recordService.create(
                owner, prepared("owned-message", "owned-fingerprint"), quote()).task();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        generationService.getForUser(stranger, task.getTaskCode()))
                .isInstanceOf(ForbiddenException.class);
    }

    private UserAccount createUser(String email) {
        UserAccount user = new UserAccount();
        user.setEmail(email);
        user.setUsername(email);
        user.setRole("USER");
        user.setStatus("ACTIVE");
        user.setTotalCredits(1000);
        user.setUsedCredits(0);
        return userRepository.saveAndFlush(user);
    }

    private PreparedVideoRequest prepared(String clientMessageId, String fingerprint) {
        return new PreparedVideoRequest(
                "A cinematic sunrise",
                "mm2-seedance2.0",
                4,
                "9:16",
                "720p",
                clientMessageId,
                fingerprint,
                VideoGenerationType.TEXT_TO_VIDEO,
                List.of(),
                Map.of(
                        "model", "mm2-seedance2.0",
                        "prompt", "A cinematic sunrise",
                        "duration", 4,
                        "aspect_ratio", "9:16"));
    }

    private VideoModelService.Quote quote() {
        return new VideoModelService.Quote(new ProviderModel(
                "mm2-seedance2.0",
                "Seedance 2.0",
                "video",
                List.of("text-to-video"),
                List.of("prompt"),
                new BigDecimal("17")), 68);
    }

    private <T> T get(Future<T> future) {
        try {
            return future.get();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
