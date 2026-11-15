package com.example.chatseedance2.service;

import com.example.chatseedance2.config.VideoProperties;
import com.example.chatseedance2.entity.UserAccount;
import com.example.chatseedance2.entity.VideoAssetDirection;
import com.example.chatseedance2.entity.VideoAssetMediaType;
import com.example.chatseedance2.entity.VideoAssetRole;
import com.example.chatseedance2.entity.VideoBillingStatus;
import com.example.chatseedance2.entity.VideoGenerationAsset;
import com.example.chatseedance2.entity.VideoGenerationTask;
import com.example.chatseedance2.entity.VideoTaskEvent;
import com.example.chatseedance2.entity.VideoTaskEventType;
import com.example.chatseedance2.entity.VideoTaskStatus;
import com.example.chatseedance2.exception.BusinessException;
import com.example.chatseedance2.repository.VideoGenerationAssetRepository;
import com.example.chatseedance2.repository.VideoGenerationTaskRepository;
import com.example.chatseedance2.repository.VideoTaskEventRepository;
import com.example.chatseedance2.service.provider.ProviderTaskSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class VideoTaskRecordService {

    private final VideoGenerationTaskRepository taskRepository;
    private final VideoGenerationAssetRepository assetRepository;
    private final VideoTaskEventRepository eventRepository;
    private final CreditService creditService;
    private final TaskCodeService taskCodeService;
    private final VideoProperties properties;

    @Transactional
    public CreateResult create(UserAccount requestedUser, PreparedVideoRequest request,
                               VideoModelService.Quote quote) {
        UserAccount user = creditService.lockUser(requestedUser.getId());
        VideoGenerationTask existing = findIdempotentTask(user.getId(), request);
        if (existing != null) {
            return new CreateResult(existing, false);
        }

        Integer chargedKeyId = creditService.reserve(user, quote.credits());
        VideoGenerationTask task = new VideoGenerationTask();
        task.setTaskCode(taskCodeService.generate());
        task.setUserId(user.getId());
        task.setKeyId(chargedKeyId);
        task.setUserEmail(user.getEmail());
        task.setClientMessageId(request.clientMessageId());
        task.setRequestFingerprint(request.requestFingerprint());
        task.setModel(request.model());
        task.setGenerationType(request.generationType());
        task.setPrompt(request.prompt());
        task.setDurationSeconds(request.durationSeconds());
        task.setAspectRatio(request.aspectRatio());
        task.setResolution(request.resolution());
        task.setStatus(VideoTaskStatus.SUBMITTING);
        task.setBillingStatus(VideoBillingStatus.RESERVED);
        task.setQuotedUnitCost(VideoModelService.USER_CREDITS_PER_SECOND);
        task.setReservedCredits(quote.credits());
        task.setChargedCredits(0);
        task.setRequestPayload(request.providerPayload());
        task.setNextPollAt(LocalDateTime.now());
        task = taskRepository.save(task);

        for (PreparedVideoRequest.Asset input : request.assets()) {
            VideoGenerationAsset asset = new VideoGenerationAsset();
            asset.setTaskId(task.getId());
            asset.setDirection(VideoAssetDirection.INPUT);
            asset.setMediaType(input.mediaType());
            asset.setAssetRole(input.role());
            asset.setSourceUrl(input.sourceUrl());
            asset.setLocalPath(input.localPath());
            asset.setSortOrder(input.sortOrder());
            assetRepository.save(asset);
        }
        addEvent(task, VideoTaskEventType.CREATED, null, VideoTaskStatus.SUBMITTING,
                "Video task created", null, null, null, null);
        return new CreateResult(task, true);
    }

    @Transactional(readOnly = true)
    public Optional<VideoGenerationTask> findExisting(Integer userId, String clientMessageId) {
        if (clientMessageId == null || clientMessageId.isBlank()) {
            return Optional.empty();
        }
        return taskRepository.findFirstByUserIdAndClientMessageIdOrderByCreatedAtDesc(
                userId, clientMessageId.trim());
    }

    @Transactional
    public Optional<Submission> beginSubmission(Long taskId) {
        VideoGenerationTask task = lockTask(taskId);
        if (task.getStatus().isTerminal() || task.getProviderTaskId() != null) {
            return Optional.empty();
        }
        if (task.getSubmittedAt() != null) {
            changeStatus(task, VideoTaskStatus.SUBMISSION_UNKNOWN);
            task.setNextPollAt(null);
            task.setErrorCode("SUBMISSION_OUTCOME_UNKNOWN");
            task.setErrorMessage("供应商提交结果未知，等待人工核对");
            taskRepository.save(task);
            addEvent(task, VideoTaskEventType.PROVIDER_ERROR, VideoTaskStatus.SUBMITTING,
                    VideoTaskStatus.SUBMISSION_UNKNOWN, task.getErrorMessage(), null, null, true, null);
            return Optional.empty();
        }
        task.setSubmittedAt(LocalDateTime.now());
        task.setSubmitAttempts(value(task.getSubmitAttempts()) + 1);
        task.setNextPollAt(null);
        taskRepository.save(task);
        addEvent(task, VideoTaskEventType.SUBMITTED, task.getStatus(), task.getStatus(),
                "Provider submission started", null, null, null, null);
        return Optional.of(new Submission(task.getId(), task.getTaskCode(), Map.copyOf(task.getRequestPayload())));
    }

    @Transactional
    public Optional<DispatchClaim> claimForDispatch(Long taskId, DispatchType type) {
        VideoGenerationTask task = lockTask(taskId);
        if (task.getStatus().isTerminal()) {
            return Optional.empty();
        }
        boolean submission = type == DispatchType.SUBMIT;
        if (submission != (task.getProviderTaskId() == null)) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now();
        boolean submitWithoutSchedule = submission
                && task.getStatus() == VideoTaskStatus.SUBMITTING
                && task.getNextPollAt() == null;
        if (!submitWithoutSchedule && (task.getNextPollAt() == null || task.getNextPollAt().isAfter(now))) {
            return Optional.empty();
        }
        LocalDateTime claimedUntil = now.plus(Duration.ofMillis(properties.taskLockTtlMs()));
        task.setNextPollAt(claimedUntil);
        taskRepository.save(task);
        return Optional.of(new DispatchClaim(taskId, claimedUntil));
    }

    @Transactional
    public void releaseDispatchClaim(DispatchClaim claim) {
        VideoGenerationTask task = lockTask(claim.taskId());
        if (!task.getStatus().isTerminal() && sameInstant(claim.claimedUntil(), task.getNextPollAt())) {
            task.setNextPollAt(LocalDateTime.now());
            taskRepository.save(task);
        }
    }

    @Transactional
    public void requestImmediatePoll(Long taskId) {
        VideoGenerationTask task = lockTask(taskId);
        if (!task.getStatus().isTerminal() && task.getProviderTaskId() != null) {
            task.setNextPollAt(LocalDateTime.now());
            taskRepository.save(task);
        }
    }

    @Transactional
    public void markAccepted(Long taskId, ProviderTaskSnapshot snapshot) {
        VideoGenerationTask task = lockTask(taskId);
        if (task.getStatus().isTerminal()) {
            return;
        }
        if (snapshot.id() == null || snapshot.id().isBlank()) {
            markUnknownLocked(task, "PROVIDER_TASK_ID_MISSING", "供应商已响应但没有返回任务 ID", snapshot.raw());
            return;
        }
        VideoTaskStatus previous = task.getStatus();
        task.setProviderTaskId(snapshot.id());
        task.setProviderStatus(snapshot.status());
        task.setProviderResponse(snapshot.raw());
        changeStatus(task, providerStatus(snapshot.status()));
        task.setErrorCode(null);
        task.setErrorMessage(null);
        task.setNextPollAt(LocalDateTime.now().plus(Duration.ofMillis(properties.pollIntervalMs())));
        taskRepository.save(task);
        addEvent(task, VideoTaskEventType.STATUS_CHANGED, previous, task.getStatus(),
                "Provider accepted task", null, null, null, null);
    }

    @Transactional
    public void markSubmissionUnknown(Long taskId, String code, String message, Map<String, Object> payload) {
        VideoGenerationTask task = lockTask(taskId);
        if (!task.getStatus().isTerminal()) {
            markUnknownLocked(task, code, message, payload);
        }
    }

    @Transactional
    public void applyProgress(Long taskId, ProviderTaskSnapshot snapshot) {
        VideoGenerationTask task = lockTask(taskId);
        if (task.getStatus().isTerminal()) {
            return;
        }
        VideoTaskStatus previous = task.getStatus();
        task.setProviderStatus(snapshot.status());
        task.setProviderResponse(snapshot.raw());
        task.setLastPolledAt(LocalDateTime.now());
        task.setPollAttempts(value(task.getPollAttempts()) + 1);
        changeStatus(task, providerStatus(snapshot.status()));
        task.setNextPollAt(LocalDateTime.now().plus(Duration.ofMillis(properties.pollIntervalMs())));
        task.setErrorCode(null);
        task.setErrorMessage(null);
        taskRepository.save(task);
        if (previous != task.getStatus()) {
            addEvent(task, VideoTaskEventType.STATUS_CHANGED, previous, task.getStatus(),
                    "Provider status changed", null, null, null, null);
        }
    }

    @Transactional
    public void markPollDelayed(Long taskId, String code, String message, Integer httpStatus,
                                boolean retryable, Map<String, Object> payload) {
        VideoGenerationTask task = lockTask(taskId);
        if (task.getStatus().isTerminal()) {
            return;
        }
        VideoTaskStatus previous = task.getStatus();
        task.setLastPolledAt(LocalDateTime.now());
        task.setPollAttempts(value(task.getPollAttempts()) + 1);
        task.setErrorCode(truncate(code, 120));
        task.setErrorMessage(truncate(message, 1000));
        changeStatus(task, VideoTaskStatus.POLLING_DELAYED);
        task.setNextPollAt(LocalDateTime.now().plus(Duration.ofMillis(properties.pollDelayedIntervalMs())));
        taskRepository.save(task);
        addEvent(task, VideoTaskEventType.POLL_DELAYED, previous, VideoTaskStatus.POLLING_DELAYED,
                message, httpStatus, code, retryable, payload);
    }

    @Transactional
    public boolean complete(Long taskId, ProviderTaskSnapshot snapshot) {
        Integer userId = taskRepository.findUserIdById(taskId)
                .orElseThrow(() -> new BusinessException("TASK_NOT_FOUND", "视频任务不存在"));
        UserAccount user = creditService.lockUser(userId);
        VideoGenerationTask task = lockTask(taskId);
        if (task.getStatus().isTerminal()) {
            return false;
        }
        int actualCredits = task.getReservedCredits();
        if (task.getBillingStatus() == VideoBillingStatus.RESERVED) {
            creditService.adjust(user, actualCredits - task.getReservedCredits());
        }
        VideoTaskStatus previous = task.getStatus();
        task.setProviderStatus(snapshot.status());
        task.setProviderResponse(snapshot.raw());
        task.setProviderCost(snapshot.cost());
        task.setChargedCredits(actualCredits);
        task.setBillingStatus(VideoBillingStatus.SETTLED);
        task.setCompletedAt(LocalDateTime.now());
        task.setLastPolledAt(LocalDateTime.now());
        task.setPollAttempts(value(task.getPollAttempts()) + 1);
        task.setNextPollAt(null);
        task.setErrorCode(null);
        task.setErrorMessage(null);
        task.setErrorReason(null);
        task.setErrorSuggestion(null);
        changeStatus(task, VideoTaskStatus.COMPLETED);
        taskRepository.save(task);
        replaceOutputAssets(task.getId(), snapshot.resultUrls());
        addEvent(task, VideoTaskEventType.COMPLETED, previous, VideoTaskStatus.COMPLETED,
                "Video task completed", null, null, false, null);
        return true;
    }

    @Transactional
    public boolean fail(Long taskId, ProviderTaskSnapshot.ProviderError providerError,
                        String fallbackCode, String fallbackMessage, Map<String, Object> payload) {
        Integer userId = taskRepository.findUserIdById(taskId)
                .orElseThrow(() -> new BusinessException("TASK_NOT_FOUND", "视频任务不存在"));
        UserAccount user = creditService.lockUser(userId);
        VideoGenerationTask task = lockTask(taskId);
        if (task.getStatus().isTerminal()) {
            return false;
        }
        if (task.getBillingStatus() == VideoBillingStatus.RESERVED) {
            creditService.adjust(user, -task.getReservedCredits());
            task.setBillingStatus(VideoBillingStatus.REFUNDED);
        }
        VideoTaskStatus previous = task.getStatus();
        task.setErrorCode(truncate(providerError != null ? providerError.code() : fallbackCode, 120));
        task.setErrorMessage(truncate(providerError != null ? providerError.message() : fallbackMessage, 1000));
        task.setErrorReason(truncate(providerError != null ? providerError.reason() : null, 2000));
        task.setErrorSuggestion(truncate(providerError != null ? providerError.suggestion() : null, 2000));
        task.setProviderResponse(payload);
        task.setChargedCredits(0);
        task.setCompletedAt(LocalDateTime.now());
        task.setNextPollAt(null);
        changeStatus(task, VideoTaskStatus.FAILED);
        taskRepository.save(task);
        addEvent(task, VideoTaskEventType.FAILED, previous, VideoTaskStatus.FAILED,
                task.getErrorMessage(), null, task.getErrorCode(), false, payload);
        return true;
    }

    @Transactional
    public void attachProviderTask(String taskCode, String providerTaskId) {
        Long taskId = taskRepository.findIdByTaskCode(taskCode)
                .orElseThrow(() -> new BusinessException("TASK_NOT_FOUND", "视频任务不存在"));
        VideoGenerationTask task = lockTask(taskId);
        if (task.getStatus().isTerminal()) {
            throw new BusinessException("TASK_TERMINAL", "终态任务不能绑定供应商任务");
        }
        taskRepository.findByProviderTaskId(providerTaskId).ifPresent(existing -> {
            if (!existing.getId().equals(task.getId())) {
                throw new BusinessException("PROVIDER_TASK_BOUND", "供应商任务已绑定其他记录");
            }
        });
        VideoTaskStatus previous = task.getStatus();
        task.setProviderTaskId(providerTaskId);
        task.setProviderStatus("pending");
        task.setErrorCode(null);
        task.setErrorMessage(null);
        changeStatus(task, VideoTaskStatus.PENDING);
        task.setNextPollAt(LocalDateTime.now());
        taskRepository.save(task);
        addEvent(task, VideoTaskEventType.RECOVERED, previous, VideoTaskStatus.PENDING,
                "Provider task bound by administrator", null, null, null, null);
    }

    private VideoGenerationTask findIdempotentTask(Integer userId, PreparedVideoRequest request) {
        if (request.clientMessageId() != null) {
            Optional<VideoGenerationTask> existing = taskRepository
                    .findFirstByUserIdAndClientMessageIdOrderByCreatedAtDesc(userId, request.clientMessageId());
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        LocalDateTime cutoff = LocalDateTime.now()
                .minus(Duration.ofMillis(Math.max(0L, properties.debounceWindowMs())));
        return taskRepository
                .findFirstByUserIdAndRequestFingerprintAndCreatedAtAfterOrderByCreatedAtDesc(
                        userId, request.requestFingerprint(), cutoff)
                .orElse(null);
    }

    private void markUnknownLocked(VideoGenerationTask task, String code, String message,
                                   Map<String, Object> payload) {
        VideoTaskStatus previous = task.getStatus();
        task.setErrorCode(truncate(code, 120));
        task.setErrorMessage(truncate(message, 1000));
        task.setProviderResponse(payload);
        task.setNextPollAt(null);
        changeStatus(task, VideoTaskStatus.SUBMISSION_UNKNOWN);
        taskRepository.save(task);
        addEvent(task, VideoTaskEventType.PROVIDER_ERROR, previous, VideoTaskStatus.SUBMISSION_UNKNOWN,
                message, null, code, true, payload);
    }

    private void replaceOutputAssets(Long taskId, List<String> resultUrls) {
        List<VideoGenerationAsset> oldOutputs = assetRepository.findByTaskIdOrderBySortOrderAsc(taskId).stream()
                .filter(asset -> asset.getDirection() == VideoAssetDirection.OUTPUT)
                .toList();
        assetRepository.deleteAll(oldOutputs);
        for (int index = 0; index < resultUrls.size(); index++) {
            VideoGenerationAsset output = new VideoGenerationAsset();
            output.setTaskId(taskId);
            output.setDirection(VideoAssetDirection.OUTPUT);
            output.setMediaType(VideoAssetMediaType.VIDEO);
            output.setAssetRole(VideoAssetRole.RESULT);
            output.setProviderUrl(resultUrls.get(index));
            output.setSourceUrl(resultUrls.get(index));
            output.setSortOrder(index);
            assetRepository.save(output);
        }
    }

    private VideoTaskStatus providerStatus(String providerStatus) {
        return switch (providerStatus == null ? "" : providerStatus.toLowerCase()) {
            case "pending" -> VideoTaskStatus.PENDING;
            case "processing" -> VideoTaskStatus.PROCESSING;
            default -> VideoTaskStatus.PENDING;
        };
    }

    private void changeStatus(VideoGenerationTask task, VideoTaskStatus next) {
        if (task.getStatus() != next) {
            task.setStatus(next);
            task.setLastStateChangedAt(LocalDateTime.now());
        }
    }

    private void addEvent(VideoGenerationTask task, VideoTaskEventType type,
                          VideoTaskStatus from, VideoTaskStatus to, String message,
                          Integer httpStatus, String providerErrorCode, Boolean retryable,
                          Map<String, Object> payload) {
        VideoTaskEvent event = new VideoTaskEvent();
        event.setTaskId(task.getId());
        event.setEventType(type);
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setMessage(truncate(message, 2000));
        event.setProviderHttpStatus(httpStatus);
        event.setProviderErrorCode(truncate(providerErrorCode, 120));
        event.setRetryable(retryable);
        event.setPayload(payload);
        eventRepository.save(event);
    }

    private VideoGenerationTask lockTask(Long taskId) {
        return taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new BusinessException("TASK_NOT_FOUND", "视频任务不存在"));
    }

    private int value(Integer number) {
        return number == null ? 0 : number;
    }

    private boolean sameInstant(LocalDateTime left, LocalDateTime right) {
        return left != null && right != null
                && Math.abs(Duration.between(left, right).toMillis()) < 1000L;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }

    public record CreateResult(VideoGenerationTask task, boolean created) {
    }

    public record Submission(Long taskId, String taskCode, Map<String, Object> payload) {
    }

    public enum DispatchType {
        SUBMIT,
        POLL
    }

    public record DispatchClaim(Long taskId, LocalDateTime claimedUntil) {
    }
}
