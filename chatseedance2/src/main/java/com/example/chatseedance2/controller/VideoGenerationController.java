package com.example.chatseedance2.controller;

import com.example.chatseedance2.dto.ApiResponse;
import com.example.chatseedance2.dto.UploadedVideoAssetResponse;
import com.example.chatseedance2.dto.VideoGenerationRequest;
import com.example.chatseedance2.dto.VideoGenerationResponse;
import com.example.chatseedance2.dto.VideoModelResponse;
import com.example.chatseedance2.entity.UserAccount;
import com.example.chatseedance2.entity.VideoAssetMediaType;
import com.example.chatseedance2.security.AppRole;
import com.example.chatseedance2.security.AuthService;
import com.example.chatseedance2.security.RequireRole;
import com.example.chatseedance2.service.VideoGenerationService;
import com.example.chatseedance2.service.VideoInputPreviewService;
import com.example.chatseedance2.service.VideoModelService;
import com.example.chatseedance2.service.VideoStorageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/video")
@RequireRole(AppRole.USER)
@RequiredArgsConstructor
public class VideoGenerationController {

    private final AuthService authService;
    private final VideoGenerationService generationService;
    private final VideoModelService modelService;
    private final VideoStorageService storageService;
    private final VideoInputPreviewService inputPreviewService;

    @PostMapping("/generations")
    public ApiResponse<VideoGenerationResponse> submit(
            @Valid @RequestBody VideoGenerationRequest body,
            HttpServletRequest request) {
        return ApiResponse.success("视频任务已提交", generationService.submit(user(request), body));
    }

    @GetMapping("/generations")
    public ApiResponse<List<VideoGenerationResponse>> history(HttpServletRequest request) {
        return ApiResponse.success(generationService.history(user(request)));
    }

    @GetMapping("/generations/{taskCode}")
    public ApiResponse<VideoGenerationResponse> get(
            @PathVariable String taskCode,
            HttpServletRequest request) {
        return ApiResponse.success(generationService.getForUser(user(request), taskCode));
    }

    @GetMapping("/generations/{taskCode}/inputs/{assetId}")
    public ResponseEntity<byte[]> inputPreview(
            @PathVariable String taskCode,
            @PathVariable Long assetId,
            HttpServletRequest request) {
        var asset = generationService.requireInputImageForUser(user(request), taskCode, assetId);
        var preview = inputPreviewService.load(asset);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(preview.mediaType()))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(preview.bytes());
    }

    @GetMapping("/models")
    public ApiResponse<List<VideoModelResponse>> models() {
        return ApiResponse.success(modelService.listVideoModels());
    }

    @PostMapping("/assets")
    public ApiResponse<UploadedVideoAssetResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("mediaType") VideoAssetMediaType mediaType,
            HttpServletRequest request) {
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return ApiResponse.success("素材上传成功",
                storageService.storeInput(file, mediaType, user(request), baseUrl));
    }

    private UserAccount user(HttpServletRequest request) {
        return authService.requireUser(request);
    }
}
