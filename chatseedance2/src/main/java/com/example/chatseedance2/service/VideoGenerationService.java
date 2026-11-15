package com.example.chatseedance2.service;

import com.example.chatseedance2.dto.PageResult;
import com.example.chatseedance2.dto.VideoGenerationRequest;
import com.example.chatseedance2.dto.VideoGenerationResponse;
import com.example.chatseedance2.entity.UserAccount;
import com.example.chatseedance2.entity.VideoAssetDirection;
import com.example.chatseedance2.entity.VideoAssetMediaType;
import com.example.chatseedance2.entity.VideoGenerationAsset;
import com.example.chatseedance2.entity.VideoGenerationTask;
import com.example.chatseedance2.entity.VideoTaskEvent;
import com.example.chatseedance2.exception.BusinessException;
import com.example.chatseedance2.exception.ForbiddenException;
import com.example.chatseedance2.repository.VideoGenerationTaskRepository;
import com.example.chatseedance2.repository.VideoGenerationAssetRepository;
import com.example.chatseedance2.repository.VideoTaskEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoGenerationService {

    private final VideoRequestPreparationService preparationService;
    private final VideoModelService modelService;
    private final VideoTaskRecordService recordService;
    private final VideoTaskExecutionService executionService;
    private final VideoGenerationTaskRepository taskRepository;
    private final VideoGenerationAssetRepository assetRepository;
    private final VideoTaskEventRepository eventRepository;
    private final VideoTaskMapper mapper;

    public VideoGenerationResponse submit(UserAccount user, VideoGenerationRequest request) {
        String clientMessageId = request.clientMessageId() == null ? null : request.clientMessageId().trim();
        VideoGenerationTask existing = recordService.findExisting(user.getId(), clientMessageId).orElse(null);
        if (existing != null) {
            return mapper.toResponse(existing);
        }
        PreparedVideoRequest prepared = preparationService.prepare(request, user);
        VideoModelService.Quote quote = modelService.quote(prepared.model(), prepared.durationSeconds());
        VideoTaskRecordService.CreateResult created = recordService.create(user, prepared, quote);
        if (created.created()) {
            try {
                executionService.submitAsync(created.task().getId());
            } catch (TaskRejectedException error) {
                log.warn("Video submit queued for scheduler recovery: taskCode={}", created.task().getTaskCode());
            }
        }
        return mapper.toResponse(created.task());
    }

    public VideoGenerationResponse getForUser(UserAccount user, String taskCode) {
        VideoGenerationTask task = requireTask(taskCode);
        if (!user.getId().equals(task.getUserId())) {
            throw new ForbiddenException("无权查看该视频任务");
        }
        return mapper.toResponse(task);
    }

    public VideoGenerationAsset requireInputImageForUser(UserAccount user, String taskCode, Long assetId) {
        VideoGenerationTask task = requireTask(taskCode);
        if (!user.getId().equals(task.getUserId())) {
            throw new ForbiddenException("无权查看该视频任务");
        }
        return assetRepository.findById(assetId)
                .filter(asset -> task.getId().equals(asset.getTaskId()))
                .filter(asset -> asset.getDirection() == VideoAssetDirection.INPUT)
                .filter(asset -> asset.getMediaType() == VideoAssetMediaType.IMAGE)
                .orElseThrow(() -> new BusinessException("INPUT_IMAGE_NOT_FOUND", "视频参考图不存在"));
    }

    public List<VideoGenerationResponse> history(UserAccount user) {
        return taskRepository.findTop30ByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(mapper::toResponse)
                .toList();
    }

    public PageResult<VideoGenerationResponse> adminPage(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Page<VideoGenerationTask> tasks = taskRepository.findAll(PageRequest.of(
                safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PageResult<>(
                tasks.getContent().stream().map(mapper::toResponse).toList(),
                tasks.getTotalElements(), safePage, safeSize, tasks.getTotalPages());
    }

    public VideoGenerationResponse adminGet(String taskCode) {
        return mapper.toResponse(requireTask(taskCode));
    }

    public List<VideoTaskEvent> adminEvents(String taskCode) {
        VideoGenerationTask task = requireTask(taskCode);
        return eventRepository.findTop100ByTaskIdOrderByCreatedAtDesc(task.getId());
    }

    public VideoGenerationResponse adminRecover(String taskCode) {
        VideoGenerationTask task = requireTask(taskCode);
        if (task.getStatus().isTerminal()) {
            throw new BusinessException("TASK_TERMINAL", "终态任务不需要恢复");
        }
        if (task.getProviderTaskId() != null && !task.getProviderTaskId().isBlank()) {
            recordService.requestImmediatePoll(task.getId());
            executionService.pollAsync(task.getId());
        } else if (task.getSubmittedAt() == null) {
            executionService.submitAsync(task.getId());
        } else {
            throw new BusinessException("PROVIDER_TASK_ID_REQUIRED",
                    "供应商提交结果未知，请先核对并绑定供应商任务 ID，不能直接重复提交");
        }
        return mapper.toResponse(task);
    }

    public VideoGenerationResponse adminBindProviderTask(String taskCode, String providerTaskId) {
        recordService.attachProviderTask(taskCode, providerTaskId.trim());
        VideoGenerationTask task = requireTask(taskCode);
        executionService.pollAsync(task.getId());
        return mapper.toResponse(task);
    }

    public VideoGenerationResponse adminConfirmFailed(String taskCode, String reason) {
        VideoGenerationTask task = requireTask(taskCode);
        String message = reason == null || reason.isBlank() ? "管理员确认供应商任务失败" : reason.trim();
        recordService.fail(task.getId(), null, "ADMIN_CONFIRMED_FAILED", message, null);
        return mapper.toResponse(requireTask(taskCode));
    }

    public VideoGenerationTask requireTask(String taskCode) {
        return taskRepository.findByTaskCode(taskCode)
                .orElseThrow(() -> new BusinessException("TASK_NOT_FOUND", "视频任务不存在"));
    }
}
