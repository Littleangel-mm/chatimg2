package com.example.chatseedance2.controller;

import com.example.chatseedance2.dto.AdminBindProviderTaskRequest;
import com.example.chatseedance2.dto.AdminFailVideoTaskRequest;
import com.example.chatseedance2.dto.ApiResponse;
import com.example.chatseedance2.dto.PageResult;
import com.example.chatseedance2.dto.VideoGenerationResponse;
import com.example.chatseedance2.dto.VideoTaskEventResponse;
import com.example.chatseedance2.security.AppRole;
import com.example.chatseedance2.security.RequireRole;
import com.example.chatseedance2.service.VideoGenerationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/video")
@RequireRole(AppRole.ADMIN)
@RequiredArgsConstructor
public class AdminVideoController {

    private final VideoGenerationService generationService;

    @GetMapping("/generations")
    public ApiResponse<PageResult<VideoGenerationResponse>> page(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(generationService.adminPage(page, size));
    }

    @GetMapping("/generations/{taskCode}")
    public ApiResponse<VideoGenerationResponse> get(@PathVariable String taskCode) {
        return ApiResponse.success(generationService.adminGet(taskCode));
    }

    @GetMapping("/generations/{taskCode}/events")
    public ApiResponse<List<VideoTaskEventResponse>> events(@PathVariable String taskCode) {
        return ApiResponse.success(generationService.adminEvents(taskCode).stream()
                .map(VideoTaskEventResponse::from)
                .toList());
    }

    @PostMapping("/generations/{taskCode}/recover")
    public ApiResponse<VideoGenerationResponse> recover(@PathVariable String taskCode) {
        return ApiResponse.success("恢复任务已调度", generationService.adminRecover(taskCode));
    }

    @PostMapping("/generations/{taskCode}/provider-task")
    public ApiResponse<VideoGenerationResponse> bindProviderTask(
            @PathVariable String taskCode,
            @Valid @RequestBody AdminBindProviderTaskRequest body) {
        return ApiResponse.success("供应商任务已绑定",
                generationService.adminBindProviderTask(taskCode, body.providerTaskId()));
    }

    @PostMapping("/generations/{taskCode}/confirm-failed")
    public ApiResponse<VideoGenerationResponse> confirmFailed(
            @PathVariable String taskCode,
            @Valid @RequestBody(required = false) AdminFailVideoTaskRequest body) {
        String reason = body == null ? null : body.reason();
        return ApiResponse.success("任务已确认失败并结算退款",
                generationService.adminConfirmFailed(taskCode, reason));
    }
}
